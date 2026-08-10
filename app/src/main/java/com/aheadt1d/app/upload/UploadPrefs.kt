package com.aheadt1d.app.upload

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Every field here is either a destination secret (Nightscout token/API
 * secret, a webhook auth header value) or the URL it's sent to - backed by
 * EncryptedSharedPreferences rather than this app's usual plain
 * SharedPreferences, since a leaked Nightscout token is write access to a
 * real person's medical data, not just a UI preference. Falls back to plain
 * prefs only if the Keystore-backed master key can't be created (a real,
 * if rare, failure mode on some OEM/AVD combos) - better a degraded-but-
 * working uploader than a hard crash on a feature most users won't even
 * enable.
 */
object UploadPrefs {
    private const val PREFS_NAME = "ahead_upload_prefs"

    private const val KEY_METHOD = "method"
    private const val KEY_NIGHTSCOUT_URL = "nightscout_url"
    private const val KEY_NIGHTSCOUT_TOKEN = "nightscout_token"
    private const val KEY_NIGHTSCOUT_SECRET = "nightscout_secret"
    private const val KEY_NIGHTSCOUT_DEVICE_NAME = "nightscout_device_name"
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_WEBHOOK_HEADER_NAME = "webhook_header_name"
    private const val KEY_WEBHOOK_HEADER_VALUE = "webhook_header_value"
    private const val KEY_LAST_UPLOADED_EPOCH_MS = "last_uploaded_epoch_ms"
    private const val KEY_LAST_UPLOAD_RESULT = "last_upload_result"
    private const val KEY_LAST_UPLOAD_AT_MS = "last_upload_at_ms"

    fun method(context: Context): UploadMethod =
        prefs(context).getString(KEY_METHOD, null)?.let {
            runCatching { UploadMethod.valueOf(it) }.getOrNull()
        } ?: UploadMethod.NONE

    fun setMethod(context: Context, method: UploadMethod) {
        prefs(context).edit { putString(KEY_METHOD, method.name) }
    }

    fun nightscoutUrl(context: Context): String = prefs(context).getString(KEY_NIGHTSCOUT_URL, "") ?: ""
    fun nightscoutToken(context: Context): String = prefs(context).getString(KEY_NIGHTSCOUT_TOKEN, "") ?: ""
    fun nightscoutSecret(context: Context): String = prefs(context).getString(KEY_NIGHTSCOUT_SECRET, "") ?: ""

    // Advanced-only. Defaults to "Ahead" - shown as the entry's source app in
    // Nightscout's own UI, e.g. distinguishing it from a second uploader.
    fun nightscoutDeviceName(context: Context): String =
        prefs(context).getString(KEY_NIGHTSCOUT_DEVICE_NAME, "").let { if (it.isNullOrBlank()) "Ahead" else it }

    fun saveNightscout(context: Context, url: String, token: String, secret: String, deviceName: String) {
        prefs(context).edit {
            putString(KEY_NIGHTSCOUT_URL, url.trim().trimEnd('/'))
            putString(KEY_NIGHTSCOUT_TOKEN, token.trim())
            putString(KEY_NIGHTSCOUT_SECRET, secret.trim())
            putString(KEY_NIGHTSCOUT_DEVICE_NAME, deviceName.trim())
        }
    }

    fun webhookUrl(context: Context): String = prefs(context).getString(KEY_WEBHOOK_URL, "") ?: ""
    fun webhookHeaderName(context: Context): String = prefs(context).getString(KEY_WEBHOOK_HEADER_NAME, "") ?: ""
    fun webhookHeaderValue(context: Context): String = prefs(context).getString(KEY_WEBHOOK_HEADER_VALUE, "") ?: ""

    fun saveWebhook(context: Context, url: String, headerName: String, headerValue: String) {
        prefs(context).edit {
            putString(KEY_WEBHOOK_URL, url.trim())
            putString(KEY_WEBHOOK_HEADER_NAME, headerName.trim())
            putString(KEY_WEBHOOK_HEADER_VALUE, headerValue.trim())
        }
    }

    fun lastUploadedEpochMs(context: Context): Long = prefs(context).getLong(KEY_LAST_UPLOADED_EPOCH_MS, 0L)

    fun setLastUploadedEpochMs(context: Context, epochMs: Long) {
        prefs(context).edit { putLong(KEY_LAST_UPLOADED_EPOCH_MS, epochMs) }
    }

    /** Surfaced on the settings screen so a non-technical user can see
     *  "last synced 2 minutes ago" / "last attempt failed: ..." without
     *  needing to read logcat. */
    fun recordUploadResult(context: Context, success: Boolean, detail: String?) {
        prefs(context).edit {
            putString(KEY_LAST_UPLOAD_RESULT, if (success) "OK" else "FAILED: ${detail ?: "unknown error"}")
            putLong(KEY_LAST_UPLOAD_AT_MS, System.currentTimeMillis())
        }
    }

    fun lastUploadResult(context: Context): String? = prefs(context).getString(KEY_LAST_UPLOAD_RESULT, null)
    fun lastUploadAtMs(context: Context): Long = prefs(context).getLong(KEY_LAST_UPLOAD_AT_MS, 0L)

    private fun prefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        return runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            // Degraded fallback (see class doc) - a separate, unencrypted file
            // rather than silently reusing the encrypted one's name, so a
            // later-recovered Keystore doesn't collide with mismatched formats.
            appContext.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }
}
