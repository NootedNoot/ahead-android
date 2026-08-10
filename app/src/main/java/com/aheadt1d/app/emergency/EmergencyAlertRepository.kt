package com.aheadt1d.app.emergency

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aheadt1d.app.MainActivity
import com.aheadt1d.app.R
import com.aheadt1d.app.alerts.AlertChannels
import com.aheadt1d.app.events.AppDatabase
import com.aheadt1d.app.notifications.NotificationIconFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

enum class EmergencyAlertType(val storageValue: String, val label: String) {
    HIGH("high", "high"),
    LOW("low", "low"),
    // No confirmed glucose value to report - the data feed itself stopped.
    // See AlertCoordinator.handleStale / AlertNotifier.showSignalLostAlert.
    NO_DATA("no_data", "no data");

    companion object {
        fun fromStorageValue(value: String): EmergencyAlertType =
            entries.firstOrNull { it.storageValue == value } ?: HIGH
    }
}

/**
 * Owns contact CRUD, the cooldown check, and the actual SMS send + local
 * log write. Two send paths funnel through [sendMessage]/[sendToAllEligible]:
 * RedAlertActivity's manual "Send Emergency Alert" button (which owns its own
 * confirmation dialog and SEND_SMS permission check/request), and
 * [postConfirmNotification] below, used by both background timeout receivers
 * (EmergencyAlertReceiver, CriticalLowEmergencyReceiver). Either way, this
 * object assumes permission is already granted by the time [sendMessage] is
 * called - SmsManager throws SecurityException otherwise, left to propagate
 * so the caller surfaces it.
 *
 * 2026-08-03: the background timeout receivers used to call [sendToAllEligible]
 * directly the moment their timer fired - a real signal-lost false alarm (data
 * gap, not an actual low) auto-texted a family member three times with no
 * human in the loop at all, which is not something Ryan ever actually
 * consented to per-send. [postConfirmNotification] replaces that: the timer
 * firing now only posts a notification with a "Send now" action - the actual
 * SmsManager call happens ONLY if that's tapped, via EmergencyAlertConfirmReceiver.
 * This deliberately trades away the "texts someone even if you're completely
 * unresponsive" case the timeout was originally built for, in favor of never
 * sending a real text without a real tap - Ryan's explicit call after the
 * false alarm, not an oversight.
 */
object EmergencyAlertRepository {
    private const val TAG = "EmergencyAlertRepository"

    const val CONFIRM_NOTIFICATION_ID = 2201
    private const val REQ_CONFIRM_SEND = 2202
    private const val REQ_CONFIRM_CONTENT = 2203

    private fun contactDao(context: Context) = AppDatabase.getInstance(context).emergencyContactDao()
    private fun logDao(context: Context) = AppDatabase.getInstance(context).emergencyAlertLogDao()

    fun contacts(context: Context): Flow<List<EmergencyContact>> = contactDao(context).getAll()

    fun alertLog(context: Context): Flow<List<EmergencyAlertLog>> = logDao(context).getAll()

    suspend fun addContact(context: Context, name: String, phoneNumber: String): Long =
        contactDao(context).insert(EmergencyContact(name = name, phoneNumber = phoneNumber))

    suspend fun removeContact(context: Context, contact: EmergencyContact) =
        contactDao(context).delete(contact)

    /** Contacts not currently in cooldown - the set that should actually be
     *  messaged, whether from the manual confirmation dialog or the
     *  automatic timeout. */
    fun eligibleContacts(context: Context, contacts: List<EmergencyContact>): List<EmergencyContact> =
        contacts.filterNot { EmergencyContactsPrefs.isInCooldown(context, it.id) }

    /**
     * Builds the SMS body. Always names the actual glucose number (never
     * just "low"/"high") and the alert type, per the emergency-contact
     * spec - a bare severity word isn't specific enough to convey real
     * urgency to someone who isn't looking at the app.
     *
     * [value] is the last CONFIRMED reading either way - the current one for
     * LOW/HIGH, the last-known-before-signal-loss one for NO_DATA (null only
     * if no reading has ever been recorded at all, an edge case this still
     * handles gracefully rather than crashing on a NullPointerException).
     * [rate] adds a direction word ("dropping"/"rising") for LOW/HIGH when
     * available; never fabricated for NO_DATA, since there's no current rate
     * to trust. [minutesUnacknowledged] is null for the manual send (an
     * in-the-moment confirmation, not a measured wait) and always the
     * caller's current EmergencyContactsPrefs.alertTimeoutMinutes() for the
     * automatic timeout.
     */
    fun messageFor(
        context: Context,
        alertType: EmergencyAlertType,
        value: Int?,
        rate: Double?,
        minutesUnacknowledged: Long?,
        projected: Int? = null,
    ): String {
        val userName = EmergencyContactsPrefs.userName(context)
        val timeClause = if (minutesUnacknowledged != null) ", no response in ${minutesUnacknowledged} min" else ""
        return when (alertType) {
            EmergencyAlertType.LOW, EmergencyAlertType.HIGH -> {
                val valueText = value?.let { "$it mg/dL" } ?: "an unknown level"
                val movement = movementClause(rate)
                // The projection is what makes a still-normal-looking number
                // legible as an emergency. Only included when it actually
                // says something worse than where they are now - otherwise
                // it's noise that dilutes the message.
                val projectionClause = projectionClause(alertType, value, projected)
                "Ahead alert: $userName's glucose is $valueText$movement$projectionClause — ${alertType.label}$timeClause. Please check on them or call now."
            }
            EmergencyAlertType.NO_DATA -> {
                val lastKnown = value?.let { " (last reading $it mg/dL)" } ?: ""
                "Ahead alert: $userName's glucose data has stopped updating$lastKnown$timeClause. Please check on them."
            }
        }
    }

    /**
     * Speed-graded direction, with the number. A -1.3 and a -3.2 are very
     * different emergencies and the person receiving this has no other way to
     * tell them apart - without a speed cue every alert reads the same, and a
     * contact who's seen a few mild ones learns to assume "they've got it"
     * right before the one where they haven't. Thresholds match the app's own
     * urgency language (AlertNotifier.spokenDirection uses the same 2.0
     * fast/normal split).
     */
    private fun movementClause(rate: Double?): String {
        if (rate == null || kotlin.math.abs(rate) < 0.3) return ""
        val speed = when {
            kotlin.math.abs(rate) >= 3.0 -> " very fast"
            kotlin.math.abs(rate) >= 2.0 -> " fast"
            else -> ""
        }
        val direction = if (rate < 0) "dropping" else "rising"
        val formatted = String.format(java.util.Locale.US, "%+.1f", rate)
        return " and $direction$speed ($formatted mg/dL/min)"
    }

    /** The 15-minute projection, included only when it's worse than the
     *  current value in the direction of the alert - a projection heading
     *  back toward safe would undercut the urgency it's meant to convey. */
    private fun projectionClause(alertType: EmergencyAlertType, value: Int?, projected: Int?): String {
        if (projected == null || value == null) return ""
        val worsening = when (alertType) {
            EmergencyAlertType.LOW -> projected < value
            EmergencyAlertType.HIGH -> projected > value
            EmergencyAlertType.NO_DATA -> false
        }
        if (!worsening) return ""
        return ", heading for $projected mg/dL within 15 min"
    }

    /** Sends the pre-built [message] to one contact and logs it locally. */
    suspend fun sendMessage(context: Context, contact: EmergencyContact, alertType: EmergencyAlertType, message: String) {
        SmsManager.getDefault().sendTextMessage(contact.phoneNumber, null, message, null, null)
        EmergencyContactsPrefs.markSentNow(context, contact.id)
        logDao(context).insert(
            EmergencyAlertLog(
                timestamp = System.currentTimeMillis(),
                contactId = contact.id,
                contactName = contact.name,
                alertType = alertType.storageValue
            )
        )
    }

    /** Sends [message] to every currently-eligible (not-in-cooldown) contact -
     *  used by both the manual confirmation flow and
     *  EmergencyAlertConfirmReceiver (the ONLY caller for a background-timer
     *  fire now - see the class doc), so "all contacts get the same text" is
     *  enforced in one place. */
    suspend fun sendToAllEligible(context: Context, alertType: EmergencyAlertType, message: String) {
        val eligible = eligibleContacts(context, contacts(context).first())
        for (contact in eligible) {
            runCatching { sendMessage(context, contact, alertType, message) }
                .onFailure { Log.w(TAG, "failed to auto-text ${contact.name}", it) }
        }
    }

    /**
     * Posts a notification with a "Send now" action instead of texting
     * anyone - called by EmergencyAlertReceiver/CriticalLowEmergencyReceiver
     * when their background timer fires. Nothing is sent until that action
     * is tapped (EmergencyAlertConfirmReceiver does the actual send); simply
     * receiving this notification never results in an SMS on its own.
     *
     * Deliberately does NOT check SEND_SMS here - that's checked at the
     * moment of the actual tap instead (same reasoning as the old pre-check:
     * a background receiver can't prompt for a runtime permission, but a
     * missing permission shouldn't hide the prompt entirely, since the
     * confirm receiver can now surface that as a clear "grant it in the app"
     * message instead of just silently doing nothing).
     */
    fun postConfirmNotification(context: Context, alertType: EmergencyAlertType, message: String) {
        AlertChannels.ensure(context)

        val sendIntent = Intent(context, EmergencyAlertConfirmReceiver::class.java)
            .setAction(EmergencyAlertConfirmReceiver.ACTION_CONFIRM_SEND)
            .putExtra(EmergencyAlertConfirmReceiver.EXTRA_ALERT_TYPE, alertType.storageValue)
            .putExtra(EmergencyAlertConfirmReceiver.EXTRA_MESSAGE, message)
        val sendPendingIntent = PendingIntent.getBroadcast(
            context, REQ_CONFIRM_SEND, sendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = PendingIntent.getActivity(
            context, REQ_CONFIRM_CONTENT,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, AlertChannels.currentRedChannelId(context))
            .setSmallIcon(NotificationIconFactory.warningIcon(context))
            .setContentTitle("Send emergency alert to your contacts?")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.low))
            .setContentIntent(contentIntent)
            .addAction(Notification.Action.Builder(null, "Send now", sendPendingIntent).build())
            .build()

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(CONFIRM_NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "posted send-confirmation notification ($alertType) - no text sent yet")
    }
}
