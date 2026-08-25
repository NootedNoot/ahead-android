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
import com.aheadt1d.app.health.StepTracker
import com.aheadt1d.app.state.LatestTrend
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.state.RawReading
import com.aheadt1d.app.state.ReadBlockedReason
import com.aheadt1d.app.state.TREND_MATCH_TOLERANCE_MS
import com.aheadt1d.app.state.effectiveRatePerMinute
import com.aheadt1d.app.state.isStale
import com.aheadt1d.app.state.minutesSinceReading
import org.aheadt1d.ratemath.RateMath
import org.aheadt1d.ratemath.SeverityEngine
import com.aheadt1d.app.work.AlarmScheduler
import com.aheadt1d.app.work.GlucoseCheckRunner
import com.aheadt1d.app.work.WorkScheduler
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
        // On-device step tracking (see StepTracker's doc) - registered here
        // rather than owning its own service, since a step-counter listener
        // only accumulates while something's actively registered, and this
        // is the one component in the app guaranteed to keep running. No-op
        // if ACTIVITY_RECOGNITION isn't granted yet or the device has no
        // step sensor.
        StepTracker.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val freshlyStarted = scope == null
        if (freshlyStarted) {
            val initialState = toDisplayState(
                this,
                LatestTrendRepository.latestRawReading.value,
                LatestTrendRepository.latestTrend.value,
                LatestTrendRepository.readBlocked.value,
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

            // Arm the exact-alarm watchdog every time the service (re)starts.
            // The alarm's own receiver reschedules the next one; this call is the
            // initial arm and a re-arm if the service came back after a kill.
            AlarmScheduler.schedule(this)

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
                    LatestTrendRepository.latestTrend,
                    LatestTrendRepository.readBlocked
                ) { raw, trend, blocked -> Triple(raw, trend, blocked) }
                    .collectLatest { (raw, trend, blocked) ->
                        while (isActive) {
                            render(raw, trend, blocked)
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
        // Watchdog nudge on an ALREADY-alive service. The fresh-start path above
        // runs cycle 1 immediately, so this only matters when the scope guard
        // no-op'd - exactly the alive-but-stalled case: the loop holds no
        // wakelock, so in deep Doze (battery exemption not granted) its delay()
        // timers can legally pause between alarm wakeups while the service
        // stays "running". One-shot on the same scope; overlapping an
        // in-flight cycle is safe - the runner's coordinators are @Synchronized
        // with dedup, same guarantee the Worker watchdog already relies on.
        if (!freshlyStarted && intent?.action == ACTION_FORCE_CHECK) {
            Log.d(TAG, "force-check nudge received - running an immediate cycle")
            scope?.launch { runCheck() }
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
    private fun render(raw: RawReading?, trend: LatestTrend?, blocked: ReadBlockedReason?) {
        try {
            val state = toDisplayState(this, raw, trend, blocked)

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

            // Level 1 & 2 Historical Vault: Persist every fresh reading to SQLite and daily archive
            if (raw != null) {
                try {
                    com.aheadt1d.app.data.GlucoseVaultDatabase.getInstance(this)
                        .recordReading(raw.time, raw.value, "HEALTH_CONNECT", trend?.rate, trend?.severity)
                    com.aheadt1d.app.data.DailyArchiveExporter.exportDay(this)
                } catch (t: Throwable) {
                    Log.w(TAG, "Vault recording non-fatal error: ${t.message}")
                }
            }

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

    /** Exact reading timestamp is part of the signature (not just value/arrow)
     *  so a new-but-numerically-identical reading still refreshes the
     *  displayed "as of" time, while repeat ticks against the same unchanged
     *  reading correctly get deduped. */
    private fun GlucoseDisplayState.signature(): Any = when (this) {
        is GlucoseDisplayState.Reading ->
            listOf<Any?>(value, arrow, deltaFromPrevious, readingTime, severity, projected, projectedExtended, ratePerMinute)
        // blockedReason is part of the signature so a diagnosis arriving (or
        // clearing) mid-stale re-renders the guidance copy without waiting for
        // the next 5-minute age bucket.
        is GlucoseDisplayState.Stale -> "stale-${ageMinutes / 5}-${blockedReason?.name ?: "gap"}"
        GlucoseDisplayState.NoData -> "no-data"
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app from recents can kill this service on many OEM skins
        // despite START_STICKY. Schedule a WorkManager one-time job to bring it
        // back - WorkManager survives the process death the swipe causes, which a
        // direct restart from here would not. This is the glucose monitor; it must
        // not silently die just because the user cleared the app from recents.
        Log.w(TAG, "task removed (swiped from recents) - scheduling service restart")
        WorkScheduler.restartSoon(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        StepTracker.stop()
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
        // 2026-08-09: lowered 90->80 to match trend-detector.js - it was firing
        // this local fallback (not just the backend) on a flat, comfortably-normal
        // ~90-94, since this fallback runs every 5-min poll whenever the backend's
        // ~15-min classification hasn't caught up yet, which per the doc above is
        // often, not rare. Forgetting this hand-synced copy the first time around
        // is exactly the trap the "duplicated on purpose" section of this repo's
        // CLAUDE.md warns about.
        private const val LOCAL_YELLOW_PROJECTED_LOW = 80
        private const val LOCAL_YELLOW_PROJECTED_HIGH = 200

        // Mirrors trend-detector.js's YELLOW_RATE_FALLING/RISING defaults - same
        // hand-synced scope note as the projection thresholds above.
        private const val LOCAL_YELLOW_RATE_FALLING = -1.5
        private const val LOCAL_YELLOW_RATE_RISING = 2.5

        // toDisplayState moved to GlucoseDisplayState.kt 2026-08-25 (pure
        // refactor) so MainActivity can build the same state for
        // PassiveContextEngine without depending on this service class -
        // still called unqualified below since both files share this package.

        /**
         * Refreshes the persistent status notification (and re-evaluates
         * AlertCoordinator) directly from whatever LatestTrendRepository
         * currently holds - independent of whether this service instance is
         * alive. Both used to only happen inside a live service's render()
         * loop, which observes the repository's Flows; GlucoseCheckWorker's
         * watchdog run (and the service's own resurrection attempt via
         * ensureRunning()) write fresh data into that repository too, but
         * ensureRunning() can silently fail to actually restart a dead
         * foreground service (starting an FGS from a background WorkManager
         * context is restricted on API 31+ and the failure is swallowed - see
         * ensureRunning's doc comment). When that happens, nothing turned the
         * Worker's fresh data into an updated notification until the user
         * opened the app (a real foreground launch, the one start path
         * Android never restricts) - matching the reported bug exactly.
         *
         * Called unconditionally from GlucoseCheckRunner.run() after every
         * check cycle, so the notification is never more than one cycle
         * behind the CGM regardless of the foreground service's actual
         * process state. If the service also happens to be alive, its own
         * render() loop will react to the same repository update and post
         * the identical content again shortly after - a harmless redundant
         * notify(), not a visible duplicate (same notification ID).
         */
        fun refreshNotification(context: Context) {
            val raw = LatestTrendRepository.latestRawReading.value
            val trend = LatestTrendRepository.latestTrend.value
            val blocked = LatestTrendRepository.readBlocked.value
            val state = toDisplayState(context, raw, trend, blocked)

            AlertCoordinator.evaluate(context, state, trend)

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                // Defensive: guarantees the channel exists even if this fires
                // before the service has ever been created once (channel
                // creation is idempotent and persists independent of service
                // lifecycle once registered).
                GlucoseNotifier.createChannel(context)
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, GlucoseNotifier.buildNotification(context, state))
                Log.d(TAG, "refreshNotification: notification re-posted for $state")
            } else {
                Log.w(TAG, "refreshNotification: POST_NOTIFICATIONS not granted - notification NOT updated")
            }
        }

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

        // Intent action for nudgeCheck below. Never in the manifest - the
        // service is exported=false and this only ever arrives via our own
        // PendingIntent-free startForegroundService call.
        const val ACTION_FORCE_CHECK = "com.aheadt1d.app.action.FORCE_CHECK"

        /**
         * [ensureRunning] plus a guaranteed prompt check cycle. A fresh start
         * already runs cycle 1 immediately; but when the service is ALREADY
         * alive, ensureRunning's scope guard makes the start a no-op - correct
         * for every ordinary caller, yet useless to the exact-alarm watchdog
         * when the loop is alive-but-stalled (deep Doze, no battery exemption:
         * the loop's delay() timers pause while the service technically keeps
         * running, and the notification quietly goes stale). This delivers
         * ACTION_FORCE_CHECK instead: onStartCommand sees the live scope and
         * launches one immediate runCheck() on it. Same swallow-and-retry
         * posture as ensureRunning if the OS refuses the start.
         */
        fun nudgeCheck(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GlucoseStatusService::class.java).setAction(ACTION_FORCE_CHECK)
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Couldn't nudge GlucoseStatusService - will retry on the next watchdog pass", e)
            }
        }
    }
}
