package com.aheadt1d.app.alerts

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aheadt1d.app.emergency.EmergencyAlertRepository
import com.aheadt1d.app.emergency.EmergencyAlertType
import com.aheadt1d.app.emergency.EmergencyContactsPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires CriticalLowSiren.EMERGENCY_CONTACT_TIMEOUT_MINUTES after the siren
 * starts, if it's still going (see CriticalLowEmergencyScheduler). Mirrors
 * com.aheadt1d.app.emergency.EmergencyAlertReceiver's exact shape (goAsync
 * for the suspend contact-list query) but is a distinct class/action so it
 * can never be confused with - or accidentally cancelled alongside - that
 * receiver's own independent timer.
 */
class CriticalLowEmergencyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TIMEOUT) return

        val message = CriticalLowEmergencyScheduler.takePendingMessage(context)
        if (message == null) {
            Log.d(TAG, "timer fired but nothing pending (siren already dismissed/resolved) - no-op")
            return
        }
        if (!EmergencyContactsPrefs.isEnabled(context)) {
            Log.d(TAG, "timer fired but the emergency-alert feature is off - no-op")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "timer fired but SEND_SMS isn't granted - cannot auto-text")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EmergencyAlertRepository.sendToAllEligible(context, EmergencyAlertType.LOW, message)
                Log.d(TAG, "auto-text sent (critical low siren, unacknowledged)")
            } catch (e: Exception) {
                Log.w(TAG, "auto-text send failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "CriticalLowEmergencyReceiver"
        const val ACTION_TIMEOUT = "com.aheadt1d.app.action.CRITICAL_LOW_EMERGENCY_TIMEOUT"
    }
}
