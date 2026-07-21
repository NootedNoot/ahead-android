package com.aheadt1d.app.emergency

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A designated emergency contact, picked from the system contacts app -
 *  just enough to dial/text them, nothing synced anywhere else. */
@Entity(tableName = "emergency_contacts")
data class EmergencyContact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phoneNumber: String
)

/** One row per SMS actually sent, kept for the user's own record - not
 *  shown to the contact, not synced anywhere. */
@Entity(tableName = "emergency_alert_log")
data class EmergencyAlertLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val contactId: Long,
    val contactName: String,
    val alertType: String
)
