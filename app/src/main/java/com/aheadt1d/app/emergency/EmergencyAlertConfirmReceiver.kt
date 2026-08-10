package com.aheadt1d.app.emergency

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aheadt1d.app.alerts.AlertChannels
import com.aheadt1d.app.notifications.NotificationIconFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The ONLY thing that actually calls EmergencyAlertRepository.sendToAllEligible
 * for a background-timer-originated alert - fired exclusively by the "Send
 * now" action on the notification EmergencyAlertRepository.postConfirmNotification
 * posts. See that function's doc for why this exists: a real signal-lost
 * false alarm auto-texted a family member with no human confirmation at all,
 * which is not something that should ever happen again regardless of how the
 * alert was triggered.
 *
 * SEND_SMS is checked HERE, not when the notification was posted - a missing
 * grant at post time shouldn't hide the prompt, since the permission could be
 * granted (via the app's own Emergency Contacts screen) any time before the
 * tap. If it's still missing at tap time, this replaces the notification with
 * a message telling the user to grant it, rather than silently doing nothing
 * (the old pre-check's failure mode, which just logged a warning nobody saw).
 */
class EmergencyAlertConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIRM_SEND) return

        NotificationManagerCompat.from(context).cancel(EmergencyAlertRepository.CONFIRM_NOTIFICATION_ID)

        val typeStr = intent.getStringExtra(EXTRA_ALERT_TYPE)
        val message = intent.getStringExtra(EXTRA_MESSAGE)
        if (typeStr == null || message == null) {
            Log.w(TAG, "confirm-send tapped but extras missing - stale/malformed intent, no-op")
            return
        }
        val alertType = EmergencyAlertType.fromStorageValue(typeStr)

        if (!EmergencyContactsPrefs.isEnabled(context)) {
            Log.d(TAG, "confirm-send tapped but the emergency-alert feature is off - no-op")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "confirm-send tapped but SEND_SMS isn't granted - cannot send")
            postPermissionNeededNotification(context)
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EmergencyAlertRepository.sendToAllEligible(context, alertType, message)
                Log.d(TAG, "confirmed text sent ($alertType)")
            } catch (e: Exception) {
                Log.w(TAG, "confirmed send failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Replaces the (already-cancelled) confirm notification with a plain
     *  explanation instead of leaving the tap looking like it did nothing -
     *  opens Emergency Contacts on tap, where the SEND_SMS grant flow lives. */
    private fun postPermissionNeededNotification(context: Context) {
        AlertChannels.ensure(context)
        val contentIntent = PendingIntent.getActivity(
            context, REQ_PERMISSION_CONTENT,
            Intent(context, EmergencyContactsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, AlertChannels.currentRedChannelId(context))
            .setSmallIcon(NotificationIconFactory.warningIcon(context))
            .setContentTitle("Couldn't send - SMS permission not granted")
            .setContentText("Tap to open Emergency Contacts and grant it, then try again.")
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(EmergencyAlertRepository.CONFIRM_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "EmergencyAlertConfirmReceiver"
        private const val REQ_PERMISSION_CONTENT = 2204
        const val ACTION_CONFIRM_SEND = "com.aheadt1d.app.action.EMERGENCY_ALERT_CONFIRM_SEND"
        const val EXTRA_ALERT_TYPE = "alert_type"
        const val EXTRA_MESSAGE = "message"
    }
}
