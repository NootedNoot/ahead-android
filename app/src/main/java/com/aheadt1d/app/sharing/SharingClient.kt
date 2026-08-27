package com.aheadt1d.app.sharing

import android.content.Context
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.auth.AuthPrefs
import com.aheadt1d.app.network.AuthClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owner-side sharing calls only - GET/POST/DELETE /api/shares (who I've
 * granted access to). The read side a viewer needs (GET
 * /api/shares/accessible - "whose data can I see") is only ever needed by
 * ahead-lite-android, not this app, so it deliberately isn't here.
 *
 * Kept as its own small client rather than folded into AuthClient - matches
 * the backend's own route-file split (routes/shares.js vs
 * routes/auth-routes.js/routes/devices.js), and sharing is a distinct
 * enough concern from identity/devices to warrant its own file. Reuses
 * AuthClient's exception types (AuthException/SessionExpiredException) so
 * ManageSharingActivity's error handling doesn't need a third type to
 * catch.
 */
object SharingClient {
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val baseUrl get() = BuildConfig.BACKEND_BASE_URL

    private fun callRaw(builder: Request.Builder): String {
        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string()
            if (response.code == 401) throw AuthClient.SessionExpiredException()
            if (!response.isSuccessful) {
                val message = responseBody?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                throw AuthClient.AuthException(message ?: "Request failed (${response.code})")
            }
            return responseBody ?: "{}"
        }
    }

    private fun requireJwt(context: Context) = AuthPrefs.jwt(context) ?: throw AuthClient.SessionExpiredException()

    /** Everyone I've granted access to my data - the manage-sharing list. */
    suspend fun fetchShares(context: Context): JSONArray = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("$baseUrl/api/shares")
            .addHeader("Authorization", "Bearer ${requireJwt(context)}")
            .get()
        JSONArray(callRaw(builder))
    }

    /** 404s with a clear "no account found, ask them to sign up first"
     *  message if viewerEmail doesn't have an account yet - the backend's
     *  own message, surfaced directly rather than reworded. */
    suspend fun createShare(context: Context, viewerEmail: String): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("viewerEmail", viewerEmail) }
        val builder = Request.Builder()
            .url("$baseUrl/api/shares")
            .addHeader("Authorization", "Bearer ${requireJwt(context)}")
            .post(body.toString().toRequestBody(JSON))
        JSONObject(callRaw(builder))
    }

    suspend fun revokeShare(context: Context, shareId: String): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("$baseUrl/api/shares/$shareId")
            .addHeader("Authorization", "Bearer ${requireJwt(context)}")
            .delete()
        JSONObject(callRaw(builder))
    }
}
