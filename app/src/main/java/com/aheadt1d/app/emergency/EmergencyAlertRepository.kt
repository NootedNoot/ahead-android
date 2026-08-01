package com.aheadt1d.app.emergency

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import com.aheadt1d.app.events.AppDatabase
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
 * EmergencyAlertReceiver's automatic 15-minute-unacknowledged timeout (which
 * checks SEND_SMS itself before calling in, since there's no UI to prompt
 * from a background receiver). Either way, this object assumes permission is
 * already granted by the time it's called - SmsManager throws
 * SecurityException otherwise, left to propagate so the caller surfaces it.
 */
object EmergencyAlertRepository {
    private const val TAG = "EmergencyAlertRepository"

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
     *  used by both the manual confirmation flow and the automatic 15-minute
     *  timeout, so "all contacts get the same text" is enforced in one place. */
    suspend fun sendToAllEligible(context: Context, alertType: EmergencyAlertType, message: String) {
        val eligible = eligibleContacts(context, contacts(context).first())
        for (contact in eligible) {
            runCatching { sendMessage(context, contact, alertType, message) }
                .onFailure { Log.w(TAG, "failed to auto-text ${contact.name}", it) }
        }
    }
}
