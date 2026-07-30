package com.aheadt1d.app.emergency

import android.content.Context
import androidx.core.content.edit

/**
 * Feature toggle + cooldown + per-contact "last sent" bookkeeping, following
 * the same SharedPreferences-object pattern as VoiceAlertPrefs/SetupPrefs.
 * Defaults to OFF: this feature sends a real SMS, so it must be an explicit
 * opt-in rather than something that starts working the moment a contact is
 * added.
 */
object EmergencyContactsPrefs {
    private const val PREFS_NAME = "ahead_emergency_contacts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_COOLDOWN_MINUTES = "cooldown_minutes"
    private const val KEY_ALERT_TIMEOUT_MINUTES = "alert_timeout_minutes"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_LAST_SENT_PREFIX = "last_sent_"

    private const val DEFAULT_COOLDOWN_MINUTES = 15

    // How long a red alert may go unacknowledged before the auto-text fires
    // (EmergencyAlertScheduler/AlertCoordinator). A DIFFERENT concept from
    // cooldownMinutes above despite sharing the same default number - that one
    // paces repeat texts to the same contact after a send has already
    // happened; this one is "how long do you get to dismiss it yourself
    // first." Both are exposed as separate fields on the settings screen for
    // exactly that reason - a single "cooldown" field would leave no way to
    // control (or even see) this delay at all.
    private const val DEFAULT_ALERT_TIMEOUT_MINUTES = 15L

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun cooldownMinutes(context: Context): Int =
        prefs(context).getInt(KEY_COOLDOWN_MINUTES, DEFAULT_COOLDOWN_MINUTES)

    fun setCooldownMinutes(context: Context, minutes: Int) {
        prefs(context).edit { putInt(KEY_COOLDOWN_MINUTES, minutes.coerceAtLeast(0)) }
    }

    fun alertTimeoutMinutes(context: Context): Long =
        prefs(context).getLong(KEY_ALERT_TIMEOUT_MINUTES, DEFAULT_ALERT_TIMEOUT_MINUTES)

    /** Floored at 1: zero would mean "auto-text instantly, no chance to
     *  dismiss first," which defeats the whole point of the timer. */
    fun setAlertTimeoutMinutes(context: Context, minutes: Long) {
        prefs(context).edit { putLong(KEY_ALERT_TIMEOUT_MINUTES, minutes.coerceAtLeast(1)) }
    }

    /** Free-form name substituted into the SMS body ("[User's name]'s glucose..."). */
    fun userName(context: Context): String =
        prefs(context).getString(KEY_USER_NAME, null) ?: "Ahead user"

    fun setUserName(context: Context, name: String) {
        prefs(context).edit { putString(KEY_USER_NAME, name.trim()) }
    }

    fun lastSentAt(context: Context, contactId: Long): Long =
        prefs(context).getLong(KEY_LAST_SENT_PREFIX + contactId, 0L)

    fun markSentNow(context: Context, contactId: Long) {
        prefs(context).edit { putLong(KEY_LAST_SENT_PREFIX + contactId, System.currentTimeMillis()) }
    }

    fun isInCooldown(context: Context, contactId: Long): Boolean {
        val elapsedMinutes = (System.currentTimeMillis() - lastSentAt(context, contactId)) / 60_000
        return elapsedMinutes < cooldownMinutes(context)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
