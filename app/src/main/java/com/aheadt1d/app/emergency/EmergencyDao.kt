package com.aheadt1d.app.emergency

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EmergencyContactDao {
    @Insert
    suspend fun insert(contact: EmergencyContact): Long

    @Delete
    suspend fun delete(contact: EmergencyContact)

    @Query("SELECT * FROM emergency_contacts ORDER BY id ASC")
    fun getAll(): Flow<List<EmergencyContact>>
}

@Dao
interface EmergencyAlertLogDao {
    @Insert
    suspend fun insert(log: EmergencyAlertLog): Long

    @Query("SELECT * FROM emergency_alert_log ORDER BY timestamp DESC")
    fun getAll(): Flow<List<EmergencyAlertLog>>
}
