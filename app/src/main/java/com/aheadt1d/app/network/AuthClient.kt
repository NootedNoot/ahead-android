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

    /** Thrown specifically on a 401 from a JWT-gated call - distinct from
     *  AuthException so callers CAN tell "your session expired" apart from
     *  an ordinary validation error, without having to string-match a
     *  message. AccountSettingsActivity uses this to show "log out and back
     *  in to continue" rather than a generic failure - see that class for
     *  why this stays a Toast-level nudge rather than a full inline
     *  re-auth dialog (JWT is valid 30 days; this is a rare-edge-case path,
     *  not the common one). */
    class SessionExpiredException : IOException("Your session expired - log out and back in to continue")

    /** Returns the raw response body string - some endpoints return a
     *  top-level JSON object, others (GET /api/devices) a top-level array,
     *  so parsing is left to each caller rather than assumed here. */
    private fun callRaw(builder: Request.Builder): String {
        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string()
            if (response.code == 401) throw SessionExpiredException()
            if (!response.isSuccessful) {
                // The backend's error responses are always {"error": "human-readable message"} -
                // surface that directly rather than a raw HTTP code, since these are the messages
                // that end up in front of the user (wrong password, email taken, etc).
                val message = responseBody?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                throw AuthException(message ?: "Request failed (${response.code})")
            }
            return responseBody ?: "{}"
        }
    }

    private fun postBlocking(path: String, body: JSONObject, authHeader: String? = null): JSONObject {
        val requestBuilder = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toString().toRequestBody(JSON))
        if (authHeader != null) requestBuilder.addHeader("Authorization", "Bearer $authHeader")
        return JSONObject(callRaw(requestBuilder))
    }

    private fun getBlocking(path: String, authHeader: String): String {
        val requestBuilder = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $authHeader")
            .get()
        return callRaw(requestBuilder)
    }

    private fun deleteBlocking(path: String, body: JSONObject, authHeader: String): JSONObject {
        val requestBuilder = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $authHeader")
            .delete(body.toString().toRequestBody(JSON))
        return JSONObject(callRaw(requestBuilder))
    }

    private fun requireJwt(context: Context) = AuthPrefs.jwt(context) ?: throw SessionExpiredException()

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

    /** Every device currently authorized to upload for the logged-in
     *  account - drives AccountSettingsActivity's device list. */
    suspend fun fetchDevices(context: Context): org.json.JSONArray = withContext(Dispatchers.IO) {
        org.json.JSONArray(getBlocking("/api/devices", requireJwt(context)))
    }

    /** Revoking THIS device's own key (the one currently stored in
     *  AuthPrefs) is exactly what "Log out" does at the network layer -
     *  AccountSettingsActivity's log-out handler calls this for the
     *  current device, then clears AuthPrefs regardless of the result
     *  (a network failure shouldn't trap the user in a "logged in but
     *  can't get out" state). Revoking any OTHER device (from the list)
     *  just calls this with that device's id and refreshes the list. */
    suspend fun revokeDevice(context: Context, deviceId: String): JSONObject = withContext(Dispatchers.IO) {
        postBlocking("/api/devices/$deviceId/revoke", JSONObject(), authHeader = requireJwt(context))
    }

    /** Requires password re-entry, mirroring the backend's own
     *  DELETE /api/auth/account contract - a lingering session token alone
     *  is deliberately not enough to wipe an account. */
    suspend fun deleteAccount(context: Context, password: String): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("password", password) }
        deleteBlocking("/api/auth/account", body, authHeader = requireJwt(context))
    }

    private fun persistSession(context: Context, result: JSONObject) {
        val user = result.getJSONObject("user")
        AuthPrefs.saveSession(
            context,
            jwt = result.getString("token"),
            email = user.getString("email"),
            // NOT user.optString("displayName", null) - org.json's NULL
            // sentinel's toString() is the literal string "null", so
            // optString on a key present with an explicit JSON null (which
            // is exactly what a no-display-name signup returns) silently
            // returns the STRING "null", not a real null. Confirmed live:
            // the drawer header rendered the word "null" instead of the
            // email fallback. isNull() is the only reliable check here.
            displayName = if (user.isNull("displayName")) null else user.getString("displayName"),
            isOwner = user.optBoolean("isOwner", false),
        )
    }
}
