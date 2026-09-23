package com.aheadt1d.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.state.DebugGlucoseOverride
import com.aheadt1d.app.state.LatestTrendRepository
import kotlinx.coroutines.launch

/**
 * Debug-only. Injects a synthetic reading + matching-timestamp trend into the
 * repository so the live alert chain fires on demand:
 *
 *   adb shell am broadcast -a com.aheadt1d.app.DEBUG_INJECT_TREND \
 *     -n com.aheadt1d.app/.debug.DebugTrendInjector \
 *     --es severity red --ei value 82 --ei projected 68 [--ef rate -2.8]
 *
 * Both writes use `now` as the timestamp: the fresh RawReading defeats the
 * staleness gate, and the identical LatestTrend.date defeats the
 * TREND_MATCH_TOLERANCE_MS gate, so the service treats the injected severity
 * as current. This is the real production path - the next real Worker run
 * overwrites it; inject `--es severity none` to reset. The actual push
 * lives in DebugInjection so DebugMenuActivity's in-app UI shares this exact
 * same path instead of a second copy.
 */
class DebugTrendInjector : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return

        val ctx = context.applicationContext

        if (intent.getStringExtra("action") == "cancel") {
            com.aheadt1d.app.alerts.AlertNotifier.cancelAlerts(ctx)
            val nmc = ctx.getSystemService(android.app.NotificationManager::class.java)
            Log.d(TAG, "after cancelAlerts, active=" + nmc.activeNotifications.joinToString { "${it.id}" })
            return
        }

        if (intent.getStringExtra("action") == "reset") {
            com.aheadt1d.app.alerts.AlertNotifier.cancelAlerts(ctx)
            com.aheadt1d.app.alerts.AlertNotifier.cancelPlateau(ctx)
            com.aheadt1d.app.alerts.AlertNotifier.cancelCorrection(ctx)
            ctx.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE).edit().clear().apply()
            ctx.getSharedPreferences("ahead_plateau_state", Context.MODE_PRIVATE).edit().clear().apply()
            DebugGlucoseOverride.clear()
            DebugGlucoseOverride.notifyStateChanged(ctx)
            // Best-effort: clear any test readings from ahead-backend so Ahead Lite doesn't show them
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.aheadt1d.app.network.BackendClient.deleteRecentReadings(ctx)
            }
            LatestTrendRepository.clear(ctx)
            com.aheadt1d.app.work.WorkScheduler.runOnce(ctx)
            Log.d(TAG, "Reset all debug test state and queued live check")
            return
        }

        if (intent.getStringExtra("action") == "log_correction") {
            val isLow = intent.getBooleanExtra("isLow", true)
            val now = System.currentTimeMillis()
            com.aheadt1d.app.alerts.PlateauCoordinator.onCorrectionLogged(ctx, now, explicitLow = isLow)
            Log.d(TAG, "Logged debug correction: isLow=$isLow at $now")
            return
        }

        if (intent.getStringExtra("action") == "log_exercise") {
            val minutesAgo = intent.getIntExtra("minutesAgo", 0)
            val timestamp = System.currentTimeMillis() - minutesAgo * 60_000L
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.aheadt1d.app.events.UserEventRepository.log(ctx, com.aheadt1d.app.events.EventTag.EXERCISE, timestamp = timestamp)
            }
            Log.d(TAG, "Logged debug exercise: timestamp=$timestamp ($minutesAgo min ago)")
            return
        }

        val severity = intent.getStringExtra("severity") ?: "red"
        val value = intent.getIntExtra("value", 82)
        val projected = if (intent.hasExtra("projected")) intent.getIntExtra("projected", 68) else null
        val projectedExtended = if (intent.hasExtra("projExt")) intent.getIntExtra("projExt", 68) else projected
        val rate = if (intent.hasExtra("rate")) intent.getFloatExtra("rate", -2.8f).toDouble() else -2.8
        val ageMin = intent.getIntExtra("ageMin", 0)
        val causeTier = intent.getStringExtra("causeTier")?.let {
            runCatching { org.aheadt1d.ratemath.CauseTier.valueOf(it.uppercase()) }.getOrNull()
        }

        val readingTime = java.time.Instant.now().minusSeconds(ageMin * 60L)
        val prevValue = (value - (rate * 5)).toInt().coerceIn(20, 500)
        val points = listOf(
            com.aheadt1d.app.health.GlucosePoint(readingTime.minus(java.time.Duration.ofMinutes(5)), prevValue),
            com.aheadt1d.app.health.GlucosePoint(readingTime, value)
        )
        DebugGlucoseOverride.setPoints(points)
        DebugGlucoseOverride.notifyStateChanged(ctx)

        DebugInjection.apply(ctx, severity, value, projected, projectedExtended, rate, ageMin, causeTier = causeTier)
    }

    companion object {
        private const val TAG = "DebugTrendInjector"
    }
}
