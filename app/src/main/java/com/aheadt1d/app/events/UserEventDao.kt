package com.aheadt1d.app.events

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserEventDao {
    @Insert
    suspend fun insert(event: UserEvent): Long

    @Update
    suspend fun update(event: UserEvent)

    @Delete
    suspend fun delete(event: UserEvent)

    @Query("SELECT * FROM user_events ORDER BY timestamp ASC")
    fun getAll(): Flow<List<UserEvent>>

    @Query("SELECT * FROM user_events WHERE timestamp BETWEEN :startMillis AND :endMillis ORDER BY timestamp ASC")
    fun getInRange(startMillis: Long, endMillis: Long): Flow<List<UserEvent>>
}
