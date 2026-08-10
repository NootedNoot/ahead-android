package com.aheadt1d.app.upload

import com.aheadt1d.app.health.GlucosePoint
import java.io.IOException
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Writes readings to a Nightscout instance's standard `/api/v1/entries`
 * endpoint - the same public REST API every Nightscout-compatible viewer
 * (xDrip, the official web UI, ahead-dashboard) already reads from, so
 * anything already pointed at a Nightscout site picks these up with no
 * changes on that end.
 *
 * Two auth schemes, either works, token preferred: a per-role **access
 * token** (generated in Nightscout's own admin UI, pasted in as plain text -
 * the simple/default field on the settings screen) sent as a `?token=` query
 * param, or the classic **API secret** (advanced-only - Nightscout's older
 * scheme, requires the secret to be SHA-1 hashed client-side before sending,
 * never sent in plaintext). Both are supported since which one a given
 * Nightscout instance is configured to accept varies by how it was set up.
 */
class NightscoutUploader(
    private val baseUrl: String,
    private val token: String,
    private val secret: String,
    private val deviceName: String,
) : Uploader {

    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    override suspend fun upload(points: List<GlucosePoint>): UploadResult {
        if (points.isEmpty()) return UploadResult.Success
        val entries = JSONArray(points.map { entryJson(it) })
        return request("POST", "/api/v1/entries", entries.toString())
    }

    override suspend fun testConnection(): UploadResult =
        // A real read against the authenticated endpoint, not just a bare
        // status.json ping - status.json is usually public regardless of
        // whether a token/secret is even valid, so it wouldn't actually
        // confirm the credentials work.
        request("GET", "/api/v1/entries.json?count=1", null)

    private suspend fun request(method: String, path: String, body: String?): UploadResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext UploadResult.Failure("No Nightscout site URL set")
        val url = buildString {
            append(baseUrl)
            append(path)
            if (token.isNotBlank()) {
                append(if (path.contains("?")) "&" else "?")
                append("token=").append(token)
            }
        }
        val requestBuilder = Request.Builder().url(url)
        if (secret.isNotBlank()) requestBuilder.addHeader("api-secret", sha1(secret))
        when (method) {
            "POST" -> requestBuilder.post((body ?: "").toRequestBody(JSON))
            else -> requestBuilder.get()
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    UploadResult.Success
                } else {
                    val hint = when (response.code) {
                        401, 403 -> " - check the token/secret"
                        404 -> " - check the site URL"
                        else -> ""
                    }
                    UploadResult.Failure("Nightscout returned HTTP ${response.code}$hint")
                }
            }
        } catch (e: IOException) {
            UploadResult.Failure(e.message ?: "Network error reaching Nightscout")
        }
    }

    private fun entryJson(point: GlucosePoint): JSONObject = JSONObject().apply {
        put("type", "sgv")
        put("sgv", point.sgv)
        put("date", point.time.toEpochMilli())
        put("dateString", DateTimeFormatter.ISO_INSTANT.format(point.time))
        put("device", deviceName)
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Kept here rather than only in the upload path so the settings
         *  screen's Test Connection button can reuse the exact instance the
         *  next real upload would use. */
        fun from(baseUrl: String, token: String, secret: String, deviceName: String): NightscoutUploader =
            NightscoutUploader(baseUrl.trim().trimEnd('/'), token.trim(), secret.trim(), deviceName.ifBlank { "Ahead" })
    }
}
