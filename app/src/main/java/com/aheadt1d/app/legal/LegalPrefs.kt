package com.aheadt1d.app.legal

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the user's explicit acknowledgment and agreement to Ahead's
 * Medical Disclaimer, Liability Waiver, and Covenant Not to Sue.
 */
object LegalPrefs {
    private const val PREFS_NAME = "ahead_legal_prefs"
    private const val KEY_ACCEPTED = "terms_waiver_accepted"
    private const val KEY_ACCEPTED_TIMESTAMP = "terms_waiver_accepted_timestamp"
    private const val KEY_ACCEPTED_VERSION = "terms_waiver_accepted_version"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns true only if the user has actively checked the box and accepted the waiver. */
    fun hasAcceptedWaiver(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACCEPTED, false)

    /** Records that the waiver was accepted with a permanent timestamp and version. */
    fun recordWaiverAccepted(context: Context, version: String = "0.2.0") {
        prefs(context).edit()
            .putBoolean(KEY_ACCEPTED, true)
            .putLong(KEY_ACCEPTED_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_ACCEPTED_VERSION, version)
            .apply()
    }

    fun getAcceptedTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_ACCEPTED_TIMESTAMP, 0L)

    fun getAcceptedVersion(context: Context): String? =
        prefs(context).getString(KEY_ACCEPTED_VERSION, null)

    fun resetWaiver(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
