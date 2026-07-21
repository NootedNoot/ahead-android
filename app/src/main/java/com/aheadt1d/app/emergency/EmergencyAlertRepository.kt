package com.aheadt1d.app.emergency

import android.content.Context
import android.telephony.SmsManager
import com.aheadt1d.app.events.AppDatabase
import kotlinx.coroutines.flow.Flow

enum class EmergencyAlertType(val storageValue: String, val label: String) {
    HIGH("high", "high"),
    LOW("low", "low")
}

/**
 * Owns contact CRUD, the cooldown check, and the actual SMS send + local
 * log write. The caller (RedAlertActivity) still owns the confirmation
 * dialog and the SEND_SMS permission check/request - this object assumes
 * permission is already granted by the time send() is called.
 */
object EmergencyAlertRepository {

    private fun contactDao(context: Context) = AppDatabase.getInstance(context).emergencyContactDao()
    private fun logDao(context: Context) = AppDatabase.getInstance(context).emergencyAlertLogDao()

    fun contacts(context: Context): Flow<List<EmergencyContact>> = contactDao(context).getAll()

    fun alertLog(context: Context): Flow<List<EmergencyAlertLog>> = logDao(context).getAll()

    suspend fun addContact(context: Context, name: String, phoneNumber: String): Long =
        contactDao(context).insert(EmergencyContact(name = name, phoneNumber = phoneNumber))

    suspend fun removeContact(context: Context, contact: EmergencyContact) =
        contactDao(context).delete(contact)

    /** Contacts not currently in cooldown - the set the confirmation dialog
     *  should actually offer to message. */
    fun eligibleContacts(context: Context, contacts: List<EmergencyContact>): List<EmergencyContact> =
        contacts.filterNot { EmergencyContactsPrefs.isInCooldown(context, it.id) }

    fun messageFor(context: Context, alertType: EmergencyAlertType): String =
        "This is Ahead. ${EmergencyContactsPrefs.userName(context)}'s glucose is showing a " +
            "${alertType.label} alert and may need help. Please check on them or call now."

    /**
     * Sends the SMS and logs it. Caller must have already confirmed
     * SEND_SMS is granted - SmsManager throws SecurityException otherwise,
     * which is left to propagate so the caller can surface it rather than
     * this silently no-op'ing.
     */
    suspend fun send(context: Context, contact: EmergencyContact, alertType: EmergencyAlertType) {
        val message = messageFor(context, alertType)
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
}
