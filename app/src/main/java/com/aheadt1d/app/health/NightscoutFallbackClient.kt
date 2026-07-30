package com.aheadt1d.app.health

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Secondary glucose source, read ONLY when Health Connect itself can't be
 * read on this device (not installed/unsupported, permission revoked, or a
 * transient RemoteException) - see GlucoseCheckRunner.readPoints(). Health
 * Connect stays the primary, on-device source; this exists so a phone-side HC
 * hiccup doesn't leave the monitor blind, since the same underlying CGM data
 * is already reachable from the live Nightscout feed Ahead Lite and the web
 * viewer page (ahead-dashboard/viewer.html) already read from - same account,
 * just a different read path that doesn't depend on this phone's Health
 * Connect state at all.
 */
object NightscoutFallbackClient {
    private const val TAG = "NightscoutFallback"
    private const val BASE_URL = "https://web-production-5e0b.up.railway.app"

    private val client = OkHttpClient()

    /** Mirrors HealthConnectManager.readGlucosePoints's contract: ascending
     *  by time, trimmed to the trailing [windowMinutes]. Never throws - any
     *  failure (network, parse, empty feed) returns an empty list so the
     *  caller's existing "no points" handling covers this path too. */
    suspend fun readGlucosePoints(windowMinutes: Long): List<GlucosePoint> = withContext(Dispatchers.IO) {
        runCatching {
            // Sized to the requested window rather than a fixed count -
            // callers range from GlucoseCheckRunner's 45-min backend window
            // up to MainActivity's 6-hour chart window, and Nightscout's own
            // upload cadence isn't guaranteed to be exactly 5 min anyway, so
            // overfetching a margin then trimming by time client-side is
            // cheap and safer than undershooting a wide window.
            val fetchCount = ((windowMinutes / 5) + 15).coerceIn(20, 320)
            val request = Request.Builder()
                .url("$BASE_URL/api/v1/entries.json?count=$fetchCount")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                val array = JSONArray(body)
                val cutoff = Instant.now().minus(Duration.ofMinutes(windowMinutes))
                (0 until array.length()).mapNotNull { i ->
                    val obj = array.optJSONObject(i) ?: return@mapNotNull null
                    val sgv = obj.optInt("sgv", -1)
                    val dateString = obj.optString("dateString")
                    if (sgv <= 0 || dateString.isBlank()) return@mapNotNull null
                    val time = parseTime(dateString) ?: return@mapNotNull null
                    if (time.isBefore(cutoff)) return@mapNotNull null
                    GlucosePoint(time = time, sgv = sgv)
                }.sortedBy { it.time }
            }
        }.onFailure { e -> Log.w(TAG, "Nightscout fallback fetch failed", e) }
            .getOrDefault(emptyList())
    }

    /** Nightscout's dateString is ISO-8601 but not always strictly
     *  Instant.parse-compatible (some sources omit the offset colon), so this
     *  falls back to the more permissive OffsetDateTime parser rather than
     *  ever silently dropping a real reading. */
    private fun parseTime(dateString: String): Instant? =
        runCatching { Instant.parse(dateString) }
            .getOrElse {
                runCatching { OffsetDateTime.parse(dateString, DateTimeFormatter.ISO_DATE_TIME).toInstant() }
                    .getOrNull()
            }
}
