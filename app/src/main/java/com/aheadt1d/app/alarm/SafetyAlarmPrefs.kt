package com.aheadt1d.app.alarm

import android.content.Context
import androidx.core.content.edit

/**
 * Persists Safety Alarm state for Ahead.
 * Used for wake-up alarms during sleep, alcohol consumption, medication, or post-hypo verification.
 */
object SafetyAlarmPrefs {
    private const val PREFS_NAME = "ahead_safety_alarm_prefs"
    private const val KEY_ARMED = "alarm_armed"
    private const val KEY_TRIGGER_AT_MS = "alarm_trigger_at_ms"
    private const val KEY_CUSTOM_MESSAGE = "alarm_custom_message"
    private const val KEY_PRESET_LABEL = "alarm_preset_label"
    private const val KEY_IS_RINGING = "alarm_is_ringing"

    fun isArmed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val armed = prefs.getBoolean(KEY_ARMED, false)
        if (!armed) return false
        val triggerAt = prefs.getLong(KEY_TRIGGER_AT_MS, 0L)
        // If trigger time passed by more than 15 minutes and it's not ringing, auto-clear
        val isRinging = prefs.getBoolean(KEY_IS_RINGING, false)
        if (!isRinging && triggerAt > 0 && System.currentTimeMillis() > triggerAt + 15 * 60_000L) {
            disarm(context)
            return false
        }
        return armed
    }

    fun isRinging(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_IS_RINGING, false)

    fun setRinging(context: Context, ringing: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_IS_RINGING, ringing)
        }
    }

    fun getTriggerTime(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_TRIGGER_AT_MS, 0L)

    fun getCustomMessage(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_CUSTOM_MESSAGE, "") ?: ""

    fun getPresetLabel(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PRESET_LABEL, "BG Check") ?: "BG Check"

    fun arm(context: Context, triggerAtMs: Long, customMessage: String, presetLabel: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ARMED, true)
            putLong(KEY_TRIGGER_AT_MS, triggerAtMs)
            putString(KEY_CUSTOM_MESSAGE, customMessage)
            putString(KEY_PRESET_LABEL, presetLabel)
            putBoolean(KEY_IS_RINGING, false)
        }
    }

    fun disarm(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ARMED, false)
            putLong(KEY_TRIGGER_AT_MS, 0L)
            putBoolean(KEY_IS_RINGING, false)
        }
    }

    fun getRemainingMillis(context: Context): Long {
        val trigger = getTriggerTime(context)
        if (trigger <= 0L) return 0L
        return (trigger - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun getRemainingFormatted(context: Context): String {
        val ms = getRemainingMillis(context)
        if (ms <= 0L) return "Due now"
        val totalSecs = ms / 1000L
        val hours = totalSecs / 3600L
        val mins = (totalSecs % 3600L) / 60L
        return if (hours > 0) {
            String.format("%dh %02dm left", hours, mins)
        } else {
            String.format("%d min left", mins.coerceAtLeast(1L))
        }
    }
}
