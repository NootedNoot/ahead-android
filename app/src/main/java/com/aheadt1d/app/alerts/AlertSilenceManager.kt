package com.aheadt1d.app.alerts

import android.content.Context
import androidx.core.content.edit
import com.aheadt1d.app.voice.VoiceAlertEngine

/**
 * Killswitch and temporary silence manager for all Ahead alerts.
 * When active, completely silences all tones, vibrations, voice alerts,
 * and interrupting notifications for 10, 15, 30, or 60 minutes.
 */
object AlertSilenceManager {
    private const val PREFS_NAME = "ahead_alert_silence"
    private const val KEY_SILENCED_UNTIL_MS = "silenced_until_epoch_ms"

    fun silence(context: Context, minutes: Int) {
        val untilMs = System.currentTimeMillis() + (minutes * 60_000L)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(KEY_SILENCED_UNTIL_MS, untilMs)
        }
        // Cancel all interrupting notifications immediately
        AlertNotifier.cancelRed(context)
        AlertNotifier.cancelYellow(context)
        AlertNotifier.cancelPlateau(context)
        AlertNotifier.cancelCorrection(context)
        VoiceAlertEngine.stop()
    }

    fun cancelSilence(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(KEY_SILENCED_UNTIL_MS, 0L)
        }
    }

    fun isSilenced(context: Context): Boolean {
        val untilMs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SILENCED_UNTIL_MS, 0L)
        return System.currentTimeMillis() < untilMs
    }

    fun getRemainingMinutes(context: Context): Int {
        val untilMs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SILENCED_UNTIL_MS, 0L)
        val remainingMs = untilMs - System.currentTimeMillis()
        if (remainingMs <= 0) return 0
        return ((remainingMs + 59_999L) / 60_000L).toInt()
    }
}
