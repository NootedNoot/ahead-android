package com.aheadt1d.app.emergency

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires EmergencyContactsPrefs.alertTimeoutMinutes() after a red-severity
 * alert first posted as a new episode. If the app hasn't cancelled the timer by
 * now (dismissed in-app, or the condition auto-resolved), the alert
 * genuinely went unacknowledged - text every enabled, eligible emergency
 * contact the exact message that was built when the alert fired.
 *
 * goAsync(): sending requires a Room query for the contact list (suspend),
 * and a plain BroadcastReceiver's onReceive() is not itself a coroutine scope
 * and must not return before that finishes, or the OS may freeze/kill the
 * process mid-send.
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

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            // Can't prompt for permission from a background receiver - this is
            // the exact known limitation the opt-in moment in
            // EmergencyContactsActivity (master toggle) exists to prevent.
            Log.w(TAG, "timer fired but SEND_SMS isn't granted - cannot auto-text")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EmergencyAlertRepository.sendToAllEligible(context, pending.type, pending.message)
                Log.d(TAG, "auto-text sent (${pending.type})")
            } catch (e: Exception) {
                Log.w(TAG, "auto-text send failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "EmergencyAlertReceiver"
        const val ACTION_EMERGENCY_ALERT_TIMEOUT = "com.aheadt1d.app.action.EMERGENCY_ALERT_TIMEOUT"
    }
}
