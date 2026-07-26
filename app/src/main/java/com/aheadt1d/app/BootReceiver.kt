package com.aheadt1d.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aheadt1d.app.notifications.GlucoseStatusService
import com.aheadt1d.app.work.AlarmScheduler

/**
 * Restarts the glucose monitor after the events that would otherwise leave it
 * dead until the user next opens the app:
 *  - device reboot (BOOT_COMPLETED / the OEM QUICKBOOT_POWERON variant), and
 *  - an app update (MY_PACKAGE_REPLACED - a reinstall/update stops the old
 *    process without a reboot).
 *
 * It only re-ensures the foreground service; the service's own 5-min loop takes
 * the cadence back over from there. Starting a foreground service from a
 * BOOT_COMPLETED receiver is one of the allowed background-start exemptions, and
 * ensureRunning() swallows the IllegalStateException if the OS refuses anyway -
 * in which case the periodic WorkManager watchdog (which WorkManager itself
 * reschedules after boot) is the fallback that brings the service back.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Received ${intent.action} - ensuring GlucoseStatusService is running")
                GlucoseStatusService.ensureRunning(context)
                // Re-arm the exact-alarm watchdog too: a reboot clears all pending
                // alarms, so without this the tightest resilience layer would stay
                // dead until the user next opened the app.
                AlarmScheduler.schedule(context)
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
