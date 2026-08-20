package com.aheadt1d.app.events

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

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
 *  3 - REMOVED 2026-08-20: the emergency-contact auto-text feature (and the
 *      full-screen lockout/siren it was tied to) is gone at the owner's
 *      explicit request. Drops both tables rather than just deleting the
 *      entity classes, so an existing install's schema actually matches what
 *      Room expects post-upgrade - just removing the classes without a
 *      migration would fail Room's schema validation on next launch.
 * Never rely on fallbackToDestructiveMigration - that would silently wipe a
 * user's logged events on an upgrade.
 */
@Database(
    entities = [UserEvent::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userEventDao(): UserEventDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `emergency_contacts`")
                db.execSQL("DROP TABLE IF EXISTS `emergency_alert_log`")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ahead_events.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
