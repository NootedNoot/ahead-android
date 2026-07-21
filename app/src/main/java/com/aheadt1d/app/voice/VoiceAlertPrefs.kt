package com.aheadt1d.app.voice

import android.content.Context
import androidx.core.content.edit

/**
 * Persisted voice-alert settings, following the same SharedPreferences-object
 * pattern as SetupPrefs / AlertChannels (no DataStore anywhere in this app).
 *
 * Two-level gating: the master toggle is checked first, then the per-category
 * toggle. Defaults: master ON so the feature works out of the box, RED and
 * YELLOW ON, and signal-lost OFF - "no new data" is an attention-please, not an
 * active glucose emergency, so it shouldn't speak aloud unless explicitly asked.
 */
object VoiceAlertPrefs {
    private const val PREFS_NAME = "ahead_voice_alerts"
    private const val KEY_MASTER = "master_enabled"
    private const val KEY_RED = "speak_red"
    private const val KEY_YELLOW = "speak_yellow"
    private const val KEY_SIGNAL_LOST = "speak_signal_lost"
    private const val KEY_PLATEAU = "speak_plateau"
    private const val KEY_CORRECTION = "speak_correction"

    fun isMasterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MASTER, true)

    fun setMasterEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_MASTER, enabled) }

    fun isCategoryEnabled(context: Context, category: VoiceAlertCategory): Boolean =
        prefs(context).getBoolean(keyFor(category), defaultFor(category))

    fun setCategoryEnabled(context: Context, category: VoiceAlertCategory, enabled: Boolean) =
        prefs(context).edit { putBoolean(keyFor(category), enabled) }

    private fun keyFor(category: VoiceAlertCategory): String = when (category) {
        VoiceAlertCategory.RED -> KEY_RED
        VoiceAlertCategory.YELLOW -> KEY_YELLOW
        VoiceAlertCategory.SIGNAL_LOST -> KEY_SIGNAL_LOST
        VoiceAlertCategory.PLATEAU -> KEY_PLATEAU
        VoiceAlertCategory.CORRECTION -> KEY_CORRECTION
    }

    private fun defaultFor(category: VoiceAlertCategory): Boolean = when (category) {
        VoiceAlertCategory.RED -> true
        VoiceAlertCategory.YELLOW -> true
        VoiceAlertCategory.SIGNAL_LOST -> false
        // Opt-in, same reasoning as SIGNAL_LOST: a sustained-state
        // attention-please, not an active emergency, so it shouldn't speak
        // aloud unless explicitly asked.
        VoiceAlertCategory.PLATEAU -> false
        VoiceAlertCategory.CORRECTION -> false
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
