package com.aheadt1d.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Backed by EncryptedSharedPreferences, same pattern as upload/UploadPrefs.kt
 * verbatim - a leaked JWT or device API key is direct read/write access to a
 * real person's glucose data, not just a UI preference. Falls back to plain
 * prefs only if the Keystore-backed master key can't be created.
 *
 * Two separate credentials, deliberately different lifetimes:
 *  - [deviceApiKey]/[deviceId]: minted once after login, never expires
 *    (only explicit revocation kills it). THIS is what [isSetUp] checks -
 *    it's the whole "log in once and be done" property: background uploads
 *    only ever need this, never the JWT below, so they keep working
 *    indefinitely without any re-login.
 *  - [jwt]: the human session, expires in 30 days server-side. Only needed
 *    for Account Settings actions (device list, sharing, delete account) -
 *    if it's stale by the time one of those runs, that action alone
 *    re-prompts for the password; it never gates the app opening at all.
 */
object AuthPrefs {
    private const val PREFS_NAME = "ahead_auth_prefs"

    private const val KEY_JWT = "jwt"
    private const val KEY_EMAIL = "email"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_DEVICE_API_KEY = "device_api_key"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_IS_OWNER = "is_owner"

    /** The one thing MainActivity's cold-start gate checks - see this
     *  object's class doc for why it's the device key, not the JWT. */
    fun isSetUp(context: Context): Boolean = deviceApiKey(context) != null

    fun jwt(context: Context): String? = prefs(context).getString(KEY_JWT, null)
    fun email(context: Context): String? = prefs(context).getString(KEY_EMAIL, null)
    fun displayName(context: Context): String? = prefs(context).getString(KEY_DISPLAY_NAME, null)
    fun deviceApiKey(context: Context): String? = prefs(context).getString(KEY_DEVICE_API_KEY, null)
    fun deviceId(context: Context): String? = prefs(context).getString(KEY_DEVICE_ID, null)

    /** 2026-08-29: true only for the specific backend account
     *  ahead-backend's admin panel has flagged as Ryan's own (see
     *  users.is_owner's schema.sql comment) - gates the debug menu's
     *  visibility ALONGSIDE BuildConfig.DEBUG (both required), not
     *  instead of it. Defaults false, same as the server-side column, so
     *  a real caregiver account logged into a debug build never sees
     *  developer tooling meant only for Ryan. */
    fun isOwner(context: Context): Boolean = prefs(context).getBoolean(KEY_IS_OWNER, false)

    /** Shown in the drawer header / Account Settings - display name if the
     *  user set one at signup, else falls back to their email. */
    fun displayLabel(context: Context): String? = displayName(context) ?: email(context)

    fun saveSession(context: Context, jwt: String, email: String, displayName: String?, isOwner: Boolean) {
        prefs(context).edit {
            putString(KEY_JWT, jwt)
            putString(KEY_EMAIL, email)
            putString(KEY_DISPLAY_NAME, displayName)
            putBoolean(KEY_IS_OWNER, isOwner)
        }
    }

    fun saveDevice(context: Context, deviceId: String, apiKey: String) {
        prefs(context).edit {
            putString(KEY_DEVICE_ID, deviceId)
            putString(KEY_DEVICE_API_KEY, apiKey)
        }
    }

    /** Log out AND delete-account both use this - full local wipe, forcing
     *  LoginActivity's flow to run again from scratch (including a fresh
     *  device key, since the old one may no longer be valid server-side
     *  either way). */
    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }

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
            appContext.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }
}
