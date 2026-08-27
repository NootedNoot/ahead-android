package com.aheadt1d.app.network

import android.content.Context
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.auth.AuthPrefs
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Auth + device-key calls. suspend fun wrapping withContext(Dispatchers.IO)
 * internally, same convention as upload/NightscoutUploader.kt and
 * upload/WebhookUploader.kt - callers (LoginActivity, AccountSettingsActivity)
 * call these directly from lifecycleScope.launch, no manual dispatcher
 * juggling at the call site.
 *
 * signup()/login()/mintDevice() persist to AuthPrefs themselves on success -
 * callers don't need to remember to do it, and can't accidentally end up
 * with a token that was never actually saved.
 */
object AuthClient {
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val baseUrl get() = BuildConfig.BACKEND_BASE_URL

    class AuthException(message: String) : IOException(message)

    private fun postBlocking(path: String, body: JSONObject, authHeader: String? = null): JSONObject {
        val requestBuilder = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toString().toRequestBody(JSON))
        if (authHeader != null) requestBuilder.addHeader("Authorization", "Bearer $authHeader")

        client.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string()
            val json = responseBody?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (!response.isSuccessful) {
                // The backend's error responses are always {"error": "human-readable message"} -
                // surface that directly rather than a raw HTTP code, since these are the messages
                // that end up in front of the user (wrong password, email taken, etc).
                throw AuthException(json?.optString("error") ?: "Request failed (${response.code})")
            }
            return json ?: throw AuthException("Empty response from server")
        }
    }

    /** Throws AuthClient.AuthException with the backend's actual message on
     *  failure (bad password, email taken, etc) - callers show it directly. */
    suspend fun signup(context: Context, email: String, password: String, displayName: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
                if (!displayName.isNullOrBlank()) put("displayName", displayName)
            }
            val result = postBlocking("/api/auth/signup", body)
            persistSession(context, result)
            result
        }

    suspend fun login(context: Context, email: String, password: String): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }
            val result = postBlocking("/api/auth/login", body)
            persistSession(context, result)
            result
        }

    /** Mints a new device API key for whichever account is currently logged
     *  in (via the JWT just saved by signup()/login()). Called automatically
     *  right after either succeeds - see LoginActivity. */
    suspend fun mintDevice(context: Context, label: String?): JSONObject = withContext(Dispatchers.IO) {
        val jwt = AuthPrefs.jwt(context) ?: throw AuthException("Not logged in")
        val body = JSONObject().apply { if (!label.isNullOrBlank()) put("label", label) }
        val result = postBlocking("/api/devices", body, authHeader = jwt)
        AuthPrefs.saveDevice(context, result.getString("deviceId"), result.getString("apiKey"))
        result
    }

    private fun persistSession(context: Context, result: JSONObject) {
        val user = result.getJSONObject("user")
        AuthPrefs.saveSession(
            context,
            jwt = result.getString("token"),
            email = user.getString("email"),
            displayName = user.optString("displayName", null),
        )
    }
}
