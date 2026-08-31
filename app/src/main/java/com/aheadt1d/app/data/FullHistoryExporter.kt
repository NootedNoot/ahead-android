package com.aheadt1d.app.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Export everything, ever" - a dev/testing feature, not the doctor-facing
 * AGP report (`report/`, which computes clinical statistics for a care
 * team) and not [DailyArchiveExporter] (one calendar day at a time, and
 * already automatic/production). This is the raw, complete contents of
 * GlucoseVaultDatabase - every reading the vault has ever recorded, in one
 * file, for verifying the app's own behavior against its own real history
 * (see ahead-rate-math's SeverityEngineRobustnessTest-adjacent backtest
 * work for exactly that use, 2026-08-30) rather than reconstructing it by
 * hand from a folder of daily archives.
 *
 * Deliberately debug-menu-only (see DebugMenuActivity's own wiring) - this
 * isn't a feature meant for her to ever see or need; it exists for Ryan's
 * own testing.
 */
object FullHistoryExporter {
    private const val TAG = "FullHistoryExporter"

    /** Writes the vault's ENTIRE history to a single timestamped JSON+CSV
     *  pair in the same public Documents/Ahead_Archive/ directory
     *  DailyArchiveExporter already uses - one more file type in a folder
     *  she'll never need to open, not a new location to explain. Returns
     *  (null, null) if the vault is empty. */
    fun exportAll(context: Context): Pair<File?, File?> {
        val vault = GlucoseVaultDatabase.getInstance(context)
        val records = vault.getAllReadings()
        if (records.isEmpty()) {
            Log.d(TAG, "Vault is empty - nothing to export")
            return Pair(null, null)
        }

        val archiveDir = DailyArchiveExporter.getArchiveDirectory(context)
        if (!archiveDir.exists()) archiveDir.mkdirs()

        // Timestamped, not a fixed filename - a repeat export shouldn't
        // silently overwrite an earlier one she (he) might still want to
        // compare against.
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val jsonFile = File(archiveDir, "ahead_full_export_${stamp}.json")
        val csvFile = File(archiveDir, "ahead_full_export_${stamp}.csv")

        return try {
            val jsonRoot = JSONObject().apply {
                put("generated_at", System.currentTimeMillis())
                put("total_samples", records.size)
                put("earliest_epoch_ms", records.first().epochMillis)
                put("latest_epoch_ms", records.last().epochMillis)

                val array = JSONArray()
                for (r in records) {
                    array.put(
                        JSONObject().apply {
                            put("epoch_ms", r.epochMillis)
                            put("iso_time", r.isoTime)
                            put("sgv", r.sgv)
                            put("source", r.source)
                            r.rate?.let { put("rate", it) }
                            r.severity?.let { put("severity", it) }
                        }
                    )
                }
                put("readings", array)
            }
            jsonFile.writeText(jsonRoot.toString(2))

            val csvBuilder = StringBuilder("timestamp_iso,epoch_ms,sgv_mgdl,rate_per_min,severity,source\n")
            for (r in records) {
                csvBuilder.append("${r.isoTime},${r.epochMillis},${r.sgv},${r.rate ?: ""},${r.severity ?: "none"},${r.source}\n")
            }
            csvFile.writeText(csvBuilder.toString())

            Log.i(TAG, "Full history export: ${records.size} readings -> ${jsonFile.absolutePath}")
            Pair(jsonFile, csvFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write full history export: ${e.message}", e)
            Pair(null, null)
        }
    }
}
