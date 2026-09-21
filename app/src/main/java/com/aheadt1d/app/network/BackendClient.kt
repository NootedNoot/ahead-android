package com.aheadt1d.app.network

import android.content.Context
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.auth.AuthPrefs
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
     *  Requires a stored device API key (see AuthPrefs) - the backend rejects
     *  every /api/check-trend call without one. Fails fast locally with a
     *  clear message rather than sending a request that's guaranteed a 401 -
     *  this should only actually happen if AuthPrefs was cleared (e.g. a
     *  logout/delete-account) without MainActivity's login gate catching it
     *  first, which shouldn't be reachable in normal use. */
    class AuthRevokedException : IOException("Device upload authorization revoked (401)")

    fun postCheckTrend(context: Context, body: JSONObject): JSONObject {
        val apiKey = AuthPrefs.deviceApiKey(context)
            ?: throw IOException("No device key stored - not logged in")

        val request = Request.Builder()
            .url("${BuildConfig.BACKEND_BASE_URL}/api/check-trend")
            .addHeader("X-Ahead-Api-Key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) {
                throw AuthRevokedException()
            }
            val responseBody = response.body?.string()
            if (!response.isSuccessful || responseBody == null) {
                throw IOException("check-trend request failed: ${response.code}")
            }
            return JSONObject(responseBody)
        }
    }

    /**
     * Best-effort: deletes recent readings from ahead-backend (e.g. wiping
     * any test data that may have been sent) so Ahead Lite doesn't show them.
     */
    fun deleteRecentReadings(context: Context, sinceEpochMs: Long? = null) {
        val apiKey = AuthPrefs.deviceApiKey(context) ?: return
        val url = if (sinceEpochMs != null) {
            "${BuildConfig.BACKEND_BASE_URL}/api/readings?since=$sinceEpochMs"
        } else {
            "${BuildConfig.BACKEND_BASE_URL}/api/readings"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Ahead-Api-Key", apiKey)
            .delete()
            .build()
        runCatching {
            client.newCall(request).execute().close()
        }
    }
}
