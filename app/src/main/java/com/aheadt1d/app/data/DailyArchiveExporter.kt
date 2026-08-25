package com.aheadt1d.app.data

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Level 2 Automated Daily Document Archive Exporter for Ahead.
 *
 * Automatically aggregates and writes clean daily JSON and CSV records into
 * the user's public Documents directory (/Documents/Ahead_Archive/).
 *
 * Invariant: Files written to the public Documents directory survive app
 * uninstalls, phone backups, and operating system reinstalls.
 */
object DailyArchiveExporter {

    private const val TAG = "DailyArchiveExporter"
    private const val DIR_NAME = "Ahead_Archive"

    /**
     * Exports the requested date's readings (or today by default) into both JSON and CSV files.
     */
    fun exportDay(context: Context, date: LocalDate = LocalDate.now(ZoneId.systemDefault())): Pair<File?, File?> {
        val vault = GlucoseVaultDatabase.getInstance(context)
        val zone = ZoneId.systemDefault()
        val startMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        val records = vault.getReadingsBetween(startMillis, endMillis)
        if (records.isEmpty()) {
            Log.d(TAG, "No vault records found for date $date - skipping archive creation")
            return Pair(null, null)
        }

        val archiveDir = getArchiveDirectory(context)
        if (!archiveDir.exists()) archiveDir.mkdirs()

        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val jsonFile = File(archiveDir, "${dateStr}_readings.json")
        val csvFile = File(archiveDir, "${dateStr}_readings.csv")

        try {
            // Write JSON Archive
            val jsonRoot = JSONObject().apply {
                put("date", dateStr)
                put("generated_at", System.currentTimeMillis())
                put("total_samples", records.size)
                
                val array = JSONArray()
                for (r in records) {
                    val obj = JSONObject().apply {
                        put("epoch_ms", r.epochMillis)
                        put("iso_time", r.isoTime)
                        put("sgv", r.sgv)
                        put("source", r.source)
                        r.rate?.let { put("rate", it) }
                        r.severity?.let { put("severity", it) }
                    }
                    array.put(obj)
                }
                put("readings", array)
            }
            jsonFile.writeText(jsonRoot.toString(2))

            // Write CSV Archive (for Excel, Sheets, Autopilot Lab)
            val csvBuilder = StringBuilder()
            csvBuilder.append("timestamp_iso,epoch_ms,sgv_mgdl,rate_per_min,severity,source\n")
            for (r in records) {
                csvBuilder.append("${r.isoTime},${r.epochMillis},${r.sgv},${r.rate ?: ""},${r.severity ?: "none"},${r.source}\n")
            }
            csvFile.writeText(csvBuilder.toString())

            Log.i(TAG, "Successfully exported daily archives for $date: ${jsonFile.absolutePath} (${records.size} points)")
            return Pair(jsonFile, csvFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write daily archive files: ${e.message}", e)
            return Pair(null, null)
        }
    }

    /**
     * Resolves the permanent public Documents/Ahead_Archive directory, falling back
     * to external app files if public storage is unavailable.
     */
    fun getArchiveDirectory(context: Context): File {
        val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return if (publicDocs != null && (publicDocs.exists() || publicDocs.mkdirs())) {
            File(publicDocs, DIR_NAME)
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), DIR_NAME)
        }
    }
}
