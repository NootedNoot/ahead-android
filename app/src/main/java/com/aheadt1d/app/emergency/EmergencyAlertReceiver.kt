package com.aheadt1d.app.emergency

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires EmergencyContactsPrefs.alertTimeoutMinutes() after a red-severity
 * alert first posted as a new episode. If the app hasn't cancelled the timer
 * by now (dismissed in-app, or the condition auto-resolved), the alert
 * genuinely went unacknowledged.
 *
 * 2026-08-03: this used to text every eligible contact directly at this
 * point, no human involved. Changed after a real signal-lost false alarm
 * (data gap, not an actual low) auto-texted a family member three times -
 * see EmergencyAlertRepository.postConfirmNotification's doc. Now this only
 * posts a "Send now" prompt; nothing goes out unless that's tapped.
 */
class EmergencyAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EMERGENCY_ALERT_TIMEOUT) return

        val pending = EmergencyAlertScheduler.takePending(context)
        if (pending == null) {
            Log.d(TAG, "timer fired but nothing pending (already dismissed/resolved) - no-op")
            return
        }

        if (!EmergencyContactsPrefs.isEnabled(context)) {
            Log.d(TAG, "timer fired but the emergency-alert feature is off - no-op")
            return
        }

        EmergencyAlertRepository.postConfirmNotification(context, pending.type, pending.message)
    }

    companion object {
        private const val TAG = "EmergencyAlertReceiver"
        const val ACTION_EMERGENCY_ALERT_TIMEOUT = "com.aheadt1d.app.action.EMERGENCY_ALERT_TIMEOUT"
    }
}
