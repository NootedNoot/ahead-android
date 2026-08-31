package com.aheadt1d.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Level 1 Indestructible SQLite Black-Box Recorder for Ahead.
 *
 * Persists every continuous 5-minute CGM reading into an append-only,
 * zero-maintenance local database (ahead_vault.db).
 *
 * Invariant: Health Connect purges data after 30 days. GlucoseVaultDatabase
 * NEVER deletes or prunes readings, holding 10+ years of full glycemic history
 * in under 15 MB of storage.
 */
class GlucoseVaultDatabase(
    context: Context,
    // Test-support only: every real caller (getInstance()) uses the
    // default and gets the one real on-device vault file. Added so
    // GlucoseVaultDatabaseTimezoneTest can open a fresh, isolated DB per
    // test case instead of sharing the companion's cached singleton file
    // across test methods.
    dbName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context, dbName, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "GlucoseVault"
        private const val DATABASE_NAME = "ahead_vault.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_READINGS = "glucose_records"
        private const val COL_EPOCH_MILLIS = "epoch_millis"
        private const val COL_ISO_TIME = "iso_time"
        private const val COL_SGV = "sgv"
        private const val COL_SOURCE = "source"
        private const val COL_RATE = "rate"
        private const val COL_SEVERITY = "severity"

        @Volatile
        private var INSTANCE: GlucoseVaultDatabase? = null

        fun getInstance(context: Context): GlucoseVaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GlucoseVaultDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class VaultRecord(
        val epochMillis: Long,
        val isoTime: String,
        val sgv: Int,
        val source: String,
        val rate: Double?,
        val severity: String?
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_READINGS (
                $COL_EPOCH_MILLIS INTEGER PRIMARY KEY,
                $COL_ISO_TIME TEXT NOT NULL,
                $COL_SGV INTEGER NOT NULL,
                $COL_SOURCE TEXT NOT NULL,
                $COL_RATE REAL,
                $COL_SEVERITY TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_time ON $TABLE_READINGS($COL_EPOCH_MILLIS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations will alter table safely without dropping records.
    }

    /**
     * Records a single CGM sample into the vault. Thread-safe and duplicate-safe (CONFLICT_IGNORE).
     */
    fun recordReading(
        epochMillis: Long,
        sgv: Int,
        source: String = "G7_BLE",
        rate: Double? = null,
        severity: String? = null
    ): Boolean {
        if (sgv <= 0 || epochMillis <= 0) return false
        val isoTime = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

        return try {
            val values = ContentValues().apply {
                put(COL_EPOCH_MILLIS, epochMillis)
                put(COL_ISO_TIME, isoTime)
                put(COL_SGV, sgv)
                put(COL_SOURCE, source)
                rate?.let { put(COL_RATE, it) }
                severity?.let { put(COL_SEVERITY, it) }
            }
            val rowId = writableDatabase.insertWithOnConflict(
                TABLE_READINGS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            rowId != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record glucose point in vault: ${e.message}", e)
            false
        }
    }

    /**
     * Retrieves all recorded readings for a given 24-hour epoch range, sorted oldest -> newest.
     */
    fun getReadingsBetween(startMillis: Long, endMillis: Long): List<VaultRecord> {
        val records = mutableListOf<VaultRecord>()
        val cursor = readableDatabase.query(
            TABLE_READINGS,
            arrayOf(COL_EPOCH_MILLIS, COL_ISO_TIME, COL_SGV, COL_SOURCE, COL_RATE, COL_SEVERITY),
            "$COL_EPOCH_MILLIS >= ? AND $COL_EPOCH_MILLIS <= ?",
            arrayOf(startMillis.toString(), endMillis.toString()),
            null,
            null,
            "$COL_EPOCH_MILLIS ASC"
        )

        cursor.use {
            val idxEpoch = it.getColumnIndexOrThrow(COL_EPOCH_MILLIS)
            val idxIso = it.getColumnIndexOrThrow(COL_ISO_TIME)
            val idxSgv = it.getColumnIndexOrThrow(COL_SGV)
            val idxSource = it.getColumnIndexOrThrow(COL_SOURCE)
            val idxRate = it.getColumnIndexOrThrow(COL_RATE)
            val idxSeverity = it.getColumnIndexOrThrow(COL_SEVERITY)

            while (it.moveToNext()) {
                val rateVal = if (it.isNull(idxRate)) null else it.getDouble(idxRate)
                val sevVal = if (it.isNull(idxSeverity)) null else it.getString(idxSeverity)
                records.add(
                    VaultRecord(
                        epochMillis = it.getLong(idxEpoch),
                        isoTime = it.getString(idxIso),
                        sgv = it.getInt(idxSgv),
                        source = it.getString(idxSource),
                        rate = rateVal,
                        severity = sevVal
                    )
                )
            }
        }
        return records
    }

    data class DailySummary(
        val dateEpochDay: Long,
        val isoDate: String,
        val count: Long,
        val firstMillis: Long,
        val lastMillis: Long,
    )

    /**
     * One row per calendar day (device-local timezone) that has at least one
     * vault record, newest first - the "which days have data" enumeration
     * DailyArchiveExporter.exportDay needs a specific date for but the vault
     * itself never tracked. A single GROUP BY query rather than pulling every
     * row into Kotlin, since the vault is explicitly designed to grow to
     * 10+ years of history.
     *
     * 2026-08-25: first version grouped by substr(COL_ISO_TIME, 1, 10) - the
     * UTC date string - which only matches device-local calendar days when
     * the device IS UTC. Live-tested on a UTC-6 device and it split a single
     * real local day across two rows (both mislabeled with the same date,
     * since the label was derived from firstMillis in local time while the
     * GROUP BY key was the UTC date) - confusing, not just "a few hours off"
     * as originally assumed. SQLite's own 'localtime' modifier converts
     * using the device's current timezone at query time, so this groups by
     * date(epoch_millis/1000, 'unixepoch', 'localtime') instead - matches
     * DailyArchiveExporter's own local-midnight slicing exactly.
     */
    fun getDailySummaries(): List<DailySummary> {
        val summaries = mutableListOf<DailySummary>()
        val cursor = readableDatabase.rawQuery(
            """
            SELECT date($COL_EPOCH_MILLIS / 1000, 'unixepoch', 'localtime') AS day,
                   COUNT(*), MIN($COL_EPOCH_MILLIS), MAX($COL_EPOCH_MILLIS)
            FROM $TABLE_READINGS
            GROUP BY day
            ORDER BY day DESC
            """.trimIndent(),
            null,
        )
        cursor.use {
            while (it.moveToNext()) {
                val isoDate = it.getString(0)
                summaries.add(
                    DailySummary(
                        dateEpochDay = java.time.LocalDate.parse(isoDate).toEpochDay(),
                        isoDate = isoDate,
                        count = it.getLong(1),
                        firstMillis = it.getLong(2),
                        lastMillis = it.getLong(3),
                    )
                )
            }
        }
        return summaries
    }

    /**
     * Every reading ever recorded, oldest -> newest, no bound - the "give me
     * the whole vault" query [getReadingsBetween] doesn't offer directly.
     * ADDED 2026-08-30 for FullHistoryExporter (a debug-only "export
     * everything" feature, see that class's own doc) - the vault's own doc
     * already promises "10+ years... under 15 MB," so pulling the entire
     * table into memory at once is an accepted, deliberate tradeoff for a
     * feature that's explicitly for dev/testing use, not a hot path.
     */
    fun getAllReadings(): List<VaultRecord> = getReadingsBetween(0L, Long.MAX_VALUE)

    /**
     * Total lifetime count of unique readings safely stored in the vault.
     */
    fun totalRecordCount(): Long {
        return try {
            val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_READINGS", null)
            cursor.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}
