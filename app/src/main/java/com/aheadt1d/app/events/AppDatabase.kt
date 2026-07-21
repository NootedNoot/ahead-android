package com.aheadt1d.app.events

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import com.aheadt1d.app.emergency.EmergencyAlertLog
import com.aheadt1d.app.emergency.EmergencyAlertLogDao
import com.aheadt1d.app.emergency.EmergencyContact
import com.aheadt1d.app.emergency.EmergencyContactDao

/**
 * First Room database in this app - everything else so far is
 * SharedPreferences-backed (SetupPrefs, VoiceAlertPrefs, TuningPrefs). A real
 * table earns its keep here because event history needs to be queried by
 * time range (for the graph overlay) and exported in full (CSV), not just
 * read back as a single blob.
 *
 * Version history:
 *  1 - user_events only.
 *  2 - added emergency_contacts + emergency_alert_log for the Emergency
 *      Contact Alert feature.
 * Never rely on fallbackToDestructiveMigration - that would silently wipe a
 * user's logged events/contacts on an upgrade.
 */
@Database(
    entities = [UserEvent::class, EmergencyContact::class, EmergencyAlertLog::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userEventDao(): UserEventDao
    abstract fun emergencyContactDao(): EmergencyContactDao
    abstract fun emergencyAlertLogDao(): EmergencyAlertLogDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `emergency_contacts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`phoneNumber` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `emergency_alert_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`contactId` INTEGER NOT NULL, " +
                        "`contactName` TEXT NOT NULL, " +
                        "`alertType` TEXT NOT NULL)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ahead_events.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
