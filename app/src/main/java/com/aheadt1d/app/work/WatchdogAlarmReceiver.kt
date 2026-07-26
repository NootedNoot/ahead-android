package com.aheadt1d.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aheadt1d.app.notifications.GlucoseStatusService

/**
 * Fires on the exact-alarm heartbeat (see AlarmScheduler). Re-ensures the
 * foreground monitor is running - a no-op if it already is - then reschedules
 * the next one-shot alarm, since setExactAndAllowWhileIdle does not repeat on
 * its own. This is what brings the monitor back on OEM skins that kill the
 * service in deep idle despite START_STICKY and the WorkManager watchdog.
 *
 * Starting a foreground service from here while the app is backgrounded is
 * permitted: an app whose exact alarm fires is temporarily exempt from the
 * background-FGS-start restriction, and ensureRunning() swallows the
 * IllegalStateException on the off chance the OS still refuses.
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG) return
        Log.d(TAG, "watchdog alarm fired - ensuring service is up and rescheduling")
        GlucoseStatusService.ensureRunning(context)
        AlarmScheduler.schedule(context)
    }

    companion object {
        private const val TAG = "WatchdogAlarm"
        const val ACTION_WATCHDOG = "com.aheadt1d.app.action.WATCHDOG_ALARM"
    }
}
