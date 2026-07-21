package com.aheadt1d.app.network

import android.content.Context
import com.aheadt1d.app.BuildConfig
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object BackendClient {

    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Returns the parsed response body, e.g. { "processed": [ {date, severity, rate, projected, ...}, ... ] }.
     *  X-Ahead-Device-Id lets the (stateless) backend keep this device's trend
     *  state separate from every other caller; X-Ahead-Api-Key is the shared
     *  secret the backend rejects anonymous/unrecognized callers without. */
    fun postCheckTrend(context: Context, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("${BuildConfig.BACKEND_BASE_URL}/api/check-trend")
            .addHeader("X-Ahead-Device-Id", DeviceId.get(context))
            .addHeader("X-Ahead-Api-Key", BuildConfig.AHEAD_API_KEY)
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful || responseBody == null) {
                throw IOException("check-trend request failed: ${response.code}")
            }
            return JSONObject(responseBody)
        }
    }
}
