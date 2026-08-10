package com.aheadt1d.app.upload

import com.aheadt1d.app.health.GlucosePoint
import java.io.IOException
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
 * Generic escape hatch for anyone self-hosting something other than
 * Nightscout (or Nightscout isn't the right fit) - POSTs a plain JSON array
 * of readings to a caller-supplied URL, with one optional custom header for
 * auth (a bearer token, an API key header, whatever the receiving side
 * expects - this app has no opinion on the scheme, unlike NightscoutUploader
 * which speaks Nightscout's specific dialect).
 */
class WebhookUploader(
    private val url: String,
    private val headerName: String,
    private val headerValue: String,
) : Uploader {

    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    override suspend fun upload(points: List<GlucosePoint>): UploadResult {
        if (points.isEmpty()) return UploadResult.Success
        val payload = JSONArray(points.map { readingJson(it) })
        return post(payload.toString())
    }

    override suspend fun testConnection(): UploadResult =
        post(JSONArray().put(JSONObject().put("test", true)).toString())

    private suspend fun post(body: String): UploadResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext UploadResult.Failure("No webhook URL set")
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
        if (headerName.isNotBlank()) requestBuilder.addHeader(headerName, headerValue)

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    UploadResult.Success
                } else {
                    UploadResult.Failure("Webhook returned HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            UploadResult.Failure(e.message ?: "Network error reaching the webhook URL")
        }
    }

    private fun readingJson(point: GlucosePoint): JSONObject = JSONObject().apply {
        put("sgv", point.sgv)
        put("date", point.time.toEpochMilli())
        put("dateString", DateTimeFormatter.ISO_INSTANT.format(point.time))
    }

    companion object {
        fun from(url: String, headerName: String, headerValue: String): WebhookUploader =
            WebhookUploader(url.trim(), headerName.trim(), headerValue.trim())
    }
}
