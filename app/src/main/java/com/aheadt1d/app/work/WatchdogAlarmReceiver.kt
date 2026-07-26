package com.aheadt1d.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aheadt1d.app.notifications.GlucoseStatusService

/**
 * Fires on the exact-alarm heartbeat (see AlarmScheduler). Nudges the
 * foreground monitor via GlucoseStatusService.nudgeCheck - which both revives
 * a DEAD service (fresh start runs a check cycle immediately) and forces an
 * immediate check on an alive-but-STALLED one (deep Doze without the battery
 * exemption can pause the loop's delay() timers while the service technically
 * keeps running; plain ensureRunning would no-op on it and the notification
 * would quietly go stale). Then reschedules the next one-shot alarm, since
 * setExactAndAllowWhileIdle does not repeat on its own.
 *
 * Starting a foreground service from here while the app is backgrounded is
 * permitted: an app whose exact alarm fires is temporarily exempt from the
 * background-FGS-start restriction, and nudgeCheck() swallows the
 * IllegalStateException on the off chance the OS still refuses.
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG) return
        Log.d(TAG, "watchdog alarm fired - nudging a check cycle and rescheduling")
        GlucoseStatusService.nudgeCheck(context)
        AlarmScheduler.schedule(context)
    }

    companion object {
        private const val TAG = "WatchdogAlarm"
        const val ACTION_WATCHDOG = "com.aheadt1d.app.action.WATCHDOG_ALARM"
    }
}
