package com.aheadt1d.app.notifications

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aheadt1d.app.alerts.AlertCoordinator
import com.aheadt1d.app.state.LatestTrend
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.state.RawReading
import com.aheadt1d.app.state.TREND_MATCH_TOLERANCE_MS
import com.aheadt1d.app.state.effectiveRatePerMinute
import com.aheadt1d.app.state.minutesSinceReading
import com.aheadt1d.app.state.staleThresholdMinutes
import com.aheadt1d.app.work.GlucoseCheckRunner
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Foreground service backing the always-on glucose status notification.
 * There's no separate full-screen-alert service yet - if that lands later
 * and turns out to want its own lifecycle, split it out then rather than
 * guessing at its shape now.
 *
 * It owns notification updates end-to-end: it observes LatestTrendRepository
 * and re-renders both on new readings and on a once-a-minute tick, since
 * staleness has to be caught even when no new reading ever arrives.
 *
 * This service is the PRIMARY driver of the whole check cadence. Its own loop
 * runs the full shared pipeline (GlucoseCheckRunner) every 5 minutes -
 * Health Connect read, backend severity/projection, and the plateau/correction
 * detector - so RED/yellow and plateau alerts evaluate every 5 min, not on
 * WorkManager's ~15-min floor. GlucoseCheckWorker still exists but is demoted to
 * a WATCHDOG: its periodic run resurrects this service if an aggressive OEM
 * kills it, and its one-time job backs the manual "Check now" button. Because
 * the coordinators it feeds are @Synchronized with dedup, the watchdog and this
 * loop overlapping is safe - they never double-fire an alert. A foreground
 * service is exempt from most Doze/battery deferral that makes WorkManager an
 * unreliable primary, which is why the cadence lives here.
 *

 * Staleness, value, and time all come from latestRawReading (the raw Health
 * Connect point), NOT latestTrend - the backend dedups check-trend calls
 * server-side and can go a long time without returning anything "new" while
 * Health Connect keeps producing fresh points every run. latestTrend is only
 * used as a best-effort overlay for the rate/severity/projection, and only
 * when it's actually about the same reading (see TREND_MATCH_TOLERANCE_MS in
 * the state package).
 *
 * Declared as foregroundServiceType="specialUse" rather than "dataSync":
 * dataSync FGS instances are capped at ~6 hours of execution per rolling
 * 24h window as of Android 15 (targetSdk 35), which this - an
 * intentionally always-on status display - would blow through immediately.
 */
class GlucoseStatusService : Service() {

    private var scope: CoroutineScope? = null
    private var lastRenderedSignature: Any? = null

    override fun onCreate() {
        super.onCreate()
        GlucoseNotifier.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (scope == null) {
            val initialState = toDisplayState(
                LatestTrendRepository.latestRawReading.value,
                LatestTrendRepository.latestTrend.value,
            )
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                GlucoseNotifier.buildNotification(this, initialState),
                foregroundServiceType
            )
            lastRenderedSignature = initialState.signature()

            val newScope = CoroutineScope(Dispatchers.Default + Job())
            scope = newScope
            // If this scope's Job ever completes for ANY reason (an
            // uncaught exception in render(), the OS killing the process,
            // anything) other than a clean onDestroy, `scope` must go back
            // to null - otherwise onStartCommand's `if (scope == null)` guard
            // permanently no-ops every future ensureRunning() call (from the
            // Worker every ~15min and from MainActivity.onCreate), and the
            // render/poll loops never come back. This is the actual fix for
            // the "worker keeps running, display freezes forever" bug: this
            // check was previously the ONLY place scope was ever reset, and
            // it only ran on a clean shutdown.
            newScope.coroutineContext.job.invokeOnCompletion { cause ->
                if (cause != null && cause !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "service scope died unexpectedly - clearing so the next ensureRunning() call can recreate it", cause)
                }
                if (scope === newScope) scope = null
            }
            newScope.launch {
                combine(
                    LatestTrendRepository.latestRawReading,
                    LatestTrendRepository.latestTrend
                ) { raw, trend -> raw to trend }
                    .collectLatest { (raw, trend) ->
                        while (isActive) {
                            render(raw, trend)
                            delay(TICK_INTERVAL_MS.milliseconds)
                        }
                    }
            }
            newScope.launch {
                var cycle = 0
                while (isActive) {
                    Log.d(TAG, "check loop: cycle ${++cycle} (every ${CHECK_INTERVAL_MS / 60_000}m)")
                    runCheck()
                    delay(CHECK_INTERVAL_MS.milliseconds)
                }
                // Should only be reached when the service is destroyed. If this
                // ever logs while the service is meant to be alive, the loop has
                // stopped and the notification will freeze - that's the bug.
                Log.w(TAG, "check loop: EXITED")
            }
        }
        return START_STICKY
    }

    /**
     * The primary 5-minute cadence. Runs the full shared pipeline
     * (GlucoseCheckRunner): Health Connect read -> raw reading -> plateau/
     * correction detector -> backend severity/projection -> publish trend. The
     * render loop above observes LatestTrendRepository, so a fresh trend from
     * here is what drives both the notification refresh and AlertCoordinator -
     * meaning full RED/yellow severity and plateau alerts now evaluate every
     * 5 minutes, not only on the ~15-min WorkManager watchdog.
     */
    private suspend fun runCheck() {
        try {
            val outcome = GlucoseCheckRunner.run(this)
            Log.d(TAG, "check loop: outcome=$outcome")
        } catch (e: Throwable) {
            // ANY failure here must not propagate out and kill the loop, or the
            // persistent notification silently freezes on a stale reading until a
            // manual "Check now". GlucoseCheckRunner already handles the expected
            // Health Connect / network errors internally; this is the backstop for
            // anything unexpected. (CancellationException is rethrown so normal
            // scope shutdown still works.)
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "check loop: run failed - loop continues, will retry next cycle", e)
        }
    }

    // Broadened to Throwable for the same reason pollHealthConnect() is: this
    // runs inside the same scope's Job as the poll loop (they're siblings
    // under one plain Job, not a SupervisorJob), so an uncaught exception
    // here previously killed BOTH loops at once - the display would freeze
    // and alerts would stop firing while the Worker, a separate component,
    // kept writing fresh data underneath it. The invokeOnCompletion handler
    // in onStartCommand is the safety net if this ever still happens some
    // other way; this try/catch is preventing it in the first place.
    private fun render(raw: RawReading?, trend: LatestTrend?) {
        try {
            val state = toDisplayState(raw, trend)

            // Must run BEFORE the signature early-return below: the coordinator's
            // red re-alert cooldown depends on being called on every 60s tick,
            // even when the displayed state is byte-identical to last time.
            AlertCoordinator.evaluate(this, state, trend)

            val signature = state.signature()
            if (signature == lastRenderedSignature) {
                Log.d(TAG, "render: state unchanged, notification left as-is")
                return
            }
            lastRenderedSignature = signature

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, GlucoseNotifier.buildNotification(this, state))
                Log.d(TAG, "render: notification re-posted for $state")
            } else {
                Log.w(TAG, "render: POST_NOTIFICATIONS not granted - notification NOT updated")
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "render: failed - notification/alert skipped this cycle, will retry next tick", e)
        }
    }

    private fun toDisplayState(raw: RawReading?, trend: LatestTrend?): GlucoseDisplayState {
        if (raw == null) return GlucoseDisplayState.NoData

        val ageMinutes = minutesSinceReading(raw) ?: 0
        if (ageMinutes >= staleThresholdMinutes(this)) {
            return GlucoseDisplayState.Stale(
                lastValue = raw.value,
                lastReadingTime = raw.time,
                ageMinutes = ageMinutes,
                lastArrow = GlucoseTrendArrow.fromRatePerMinute(raw.ratePerMinute)
            )
        }

        // Primary: rate calculated on-device from the two most recent consecutive
        // Health Connect readings. Independent of the backend - never stale from
        // dedup or network issues.
        // Fallback: backend trend rate, but only trusted when its scored timestamp
        // is close enough to this raw reading's time (within tolerance) - if it's
        // stale/dedup'd its direction could be hours out of date.
        // When neither source has a rate, null → fromRatePerMinute → FLAT.
        // Rate comes from the shared effectiveRatePerMinute() - the same
        // function MainActivity displays from, so the notification and the
        // main screen can never disagree about the rate for one check cycle.
        // The same tolerance gate covers severity/projection: only trust
        // backend fields scored around this same reading.
        val trendIsCurrent = trend != null && abs(trend.date - raw.time) <= TREND_MATCH_TOLERANCE_MS

        val rate = effectiveRatePerMinute(raw, trend)

        // Local, client-side 15-min-ahead projection from this poll's own
        // raw value/rate - independent of the backend. Used only as a
        // fallback below, when the backend's trend hasn't caught up yet.
        val localProjected = rate?.let { (raw.value + it * 15).roundToInt() }

        // Hard safety floor: an actual reading this low is RED regardless of
        // what the backend's trend/projection says (mirrors SEVERE_LOW_RED_FLOOR
        // in trend-detector.js and GlucoseSeverity.kt's display floor).
        val finalSeverity = if (raw.value <= 60) {
            "red"
        } else if (trendIsCurrent) {
            trend?.severity
        } else {
            // Backend classification only runs on GlucoseCheckWorker's ~15-min
            // WorkManager cadence (true 5-min periodic work isn't available on
            // Android) or a manual Check Now - this poll runs every 5 min, so a
            // fast-moving crossing can otherwise sit unclassified for up to a
            // full Worker cycle. Falls back to a local yellow-only nudge using
            // the same 15-min projection shape, and both the projection AND
            // rate default thresholds trend-detector.js uses
            // (YELLOW_PROJECTED_LOW/HIGH, YELLOW_RATE_FALLING/RISING - kept in
            // sync by hand, no shared source of truth across the two repos).
            //
            // Deliberately capped at yellow, never red: the backend's red path
            // has noise-suppression this simple straight-line projection
            // doesn't (assessRateTrajectory dampens/suppresses on a single noisy
            // reading before trusting a RED escalation) - a false-positive
            // full-screen takeover off one bad Health Connect point would be a
            // worse failure than a several-minute-late yellow. The raw.value<=60
            // hard floor above already covers the one local-only RED case that
            // matters most (a genuinely severe low), independent of the backend.
            localFallbackYellowSeverity(localProjected, rate)
        }

        return GlucoseDisplayState.Reading(
            value = raw.value,
            arrow = GlucoseTrendArrow.fromRatePerMinute(rate),
            readingTime = raw.time,
            deltaFromPrevious = raw.deltaFromPrevious,
            trendIsComputed = rate != null,
            severity = finalSeverity,
            // Backend's own 15-min projection when it's current; otherwise the
            // local fallback projection (so the alert/notification text shown
            // for a locally-detected yellow has a real number, not "trending
            // out of range") - never both, never stale-backend-mixed-with-local.
            projected = if (trendIsCurrent) trend?.projected else localProjected,
            projectedExtended = if (trendIsCurrent) trend?.projectedExtended else null,
            ratePerMinute = rate
        )
    }

    /** See toDisplayState's fallback branch above for why this is yellow-only.
     *  Rate is checked first, independent of projection - mirrors
     *  trend-detector.js's classifySeverity ordering (rate escalation before
     *  the projection check), so a fast-moving crossing isn't missed here just
     *  because it hasn't been caught by the (possibly local, less precise)
     *  15-min projection yet. */
    private fun localFallbackYellowSeverity(projected: Int?, rate: Double?): String? {
        if (rate != null && (rate <= LOCAL_YELLOW_RATE_FALLING || rate >= LOCAL_YELLOW_RATE_RISING)) return "yellow"
        if (projected == null) return null
        return if (projected <= LOCAL_YELLOW_PROJECTED_LOW || projected >= LOCAL_YELLOW_PROJECTED_HIGH) "yellow" else null
    }

    /** Exact reading timestamp is part of the signature (not just value/arrow)
     *  so a new-but-numerically-identical reading still refreshes the
     *  displayed "as of" time, while repeat ticks against the same unchanged
     *  reading correctly get deduped. */
    private fun GlucoseDisplayState.signature(): Any = when (this) {
        is GlucoseDisplayState.Reading ->
            listOf<Any?>(value, arrow, deltaFromPrevious, readingTime, severity, projected, projectedExtended, ratePerMinute)
        is GlucoseDisplayState.Stale -> "stale-${ageMinutes / 5}"
        GlucoseDisplayState.NoData -> "no-data"
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "GlucoseStatusService"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MS = 60_000L

        // Primary check cadence. The full pipeline (Health Connect + backend
        // severity + plateau) runs on this interval; the WorkManager watchdog's
        // ~15-min period is only a resurrection backstop, no longer the driver.
        private const val CHECK_INTERVAL_MS = 5 * 60_000L

        // Mirrors trend-detector.js's YELLOW_PROJECTED_LOW/HIGH defaults - used
        // only for the local yellow-only fallback in toDisplayState, never as a
        // replacement for the backend's own (tunable) classification.
        private const val LOCAL_YELLOW_PROJECTED_LOW = 90
        private const val LOCAL_YELLOW_PROJECTED_HIGH = 200

        // Mirrors trend-detector.js's YELLOW_RATE_FALLING/RISING defaults - same
        // hand-synced scope note as the projection thresholds above.
        private const val LOCAL_YELLOW_RATE_FALLING = -1.5
        private const val LOCAL_YELLOW_RATE_RISING = 2.5

        /**
         * Best-effort: starting a foreground service can throw
         * ForegroundServiceStartNotAllowedException (a subclass of
         * IllegalStateException, API 31+) if the caller isn't currently in an
         * eligible ("foreground") app state - notably possible when called from
         * a WorkManager Worker running while the app is backgrounded. Callers
         * (GlucoseCheckWorker in particular) must never have that failure
         * propagate and abort other work, so it's swallowed here rather than
         * left to each call site.
         */
        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, GlucoseStatusService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Couldn't start GlucoseStatusService - will retry on the next check", e)
            }
        }
    }
}
