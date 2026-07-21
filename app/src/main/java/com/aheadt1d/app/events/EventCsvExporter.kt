package com.aheadt1d.app.events

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.aheadt1d.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes every logged event to a CSV in the app's cache dir and hands it off
 * via a share-sheet Intent, so it can go straight into an email/Drive/etc for
 * a provider - no cloud sync of its own (see the feature's v1 non-goals).
 */
object EventCsvExporter {

    private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    suspend fun export(context: Context): Intent {
        val events = UserEventRepository.allEvents(context).first()

        val csv = buildString {
            append("timestamp,tag,note,glucose_mg_dl\n")
            for (event in events) {
                append(TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(event.timestamp)))
                append(',')
                append(EventTag.fromStorageValue(event.tag).label)
                append(',')
                append(csvField(event.note ?: ""))
                append(',')
                append(event.glucoseAtTime?.toInt()?.toString() ?: "")
                append('\n')
            }
        }

        // File I/O is blocking - callers launch this from lifecycleScope
        // (Main dispatcher by default), so it has to hop to IO itself rather
        // than freezing the UI thread on every export tap.
        val uri = withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "ahead_events_${System.currentTimeMillis()}.csv")
            file.writeText(csv)
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        }

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Ahead event log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Quotes a field if it contains a comma, quote, or newline; doubles any
     *  embedded quotes - standard CSV escaping so a note like "stressed, busy day"
     *  doesn't corrupt the column layout. */
    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
