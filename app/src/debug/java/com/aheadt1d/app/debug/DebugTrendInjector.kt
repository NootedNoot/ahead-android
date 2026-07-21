package com.aheadt1d.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aheadt1d.app.BuildConfig

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

        // `--es action cancel` clears any posted alerts without going through a
        // severity transition. Handy for resetting a test device.
        if (intent.getStringExtra("action") == "cancel") {
            com.aheadt1d.app.alerts.AlertNotifier.cancelAlerts(ctx)
            val nmc = ctx.getSystemService(android.app.NotificationManager::class.java)
            Log.d(TAG, "after cancelAlerts, active=" + nmc.activeNotifications.joinToString { "${it.id}" })
            return
        }

        val severity = intent.getStringExtra("severity") ?: "red"
        val value = intent.getIntExtra("value", 82)
        val projected = if (intent.hasExtra("projected")) intent.getIntExtra("projected", 68) else null
        val projectedExtended = if (intent.hasExtra("projExt")) intent.getIntExtra("projExt", 68) else projected
        val rate = if (intent.hasExtra("rate")) intent.getFloatExtra("rate", -2.8f).toDouble() else -2.8
        // `--ei ageMin N` backdates the reading N minutes so the stale / signal-lost
        // path can be exercised without waiting for real data to go dark.
        val ageMin = intent.getIntExtra("ageMin", 0)

        DebugInjection.apply(ctx, severity, value, projected, projectedExtended, rate, ageMin)
    }

    companion object {
        private const val TAG = "DebugTrendInjector"
    }
}
