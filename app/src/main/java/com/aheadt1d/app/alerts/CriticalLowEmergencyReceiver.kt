package com.aheadt1d.app.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aheadt1d.app.emergency.EmergencyAlertRepository
import com.aheadt1d.app.emergency.EmergencyAlertType
import com.aheadt1d.app.emergency.EmergencyContactsPrefs

/**
 * Fires CriticalLowSiren.EMERGENCY_CONTACT_TIMEOUT_MINUTES after the siren
 * starts, if it's still going (see CriticalLowEmergencyScheduler). Distinct
 * class/action from com.aheadt1d.app.emergency.EmergencyAlertReceiver so it
 * can never be confused with - or accidentally cancelled alongside - that
 * receiver's own independent timer.
 *
 * 2026-08-03: like EmergencyAlertReceiver, this used to text directly at
 * this point with no human involved - see
 * EmergencyAlertRepository.postConfirmNotification's doc for why that
 * changed. Now this only posts a "Send now" prompt.
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

        EmergencyAlertRepository.postConfirmNotification(context, EmergencyAlertType.LOW, message)
    }

    companion object {
        private const val TAG = "CriticalLowEmergencyReceiver"
        const val ACTION_TIMEOUT = "com.aheadt1d.app.action.CRITICAL_LOW_EMERGENCY_TIMEOUT"
    }
}
