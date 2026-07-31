package com.aheadt1d.app.alerts

import android.content.Context
import androidx.core.content.edit

/**
 * Debug-only toggle to suppress the full-screen lockout takeover while
 * testing, without touching anything else - the notification, sound,
 * vibration, and voice all still fire exactly as normal; only the forced
 * lock-screen activity launch is skipped. Lives in `main` (not the debug
 * source set) because AlertNotifier/CriticalLowSiren - real production code -
 * need to read it; every read is also gated behind BuildConfig.DEBUG at the
 * call site, so this has zero effect in release regardless of this file's
 * own default.
 */
object DebugAlertPrefs {
    private const val PREFS_NAME = "ahead_debug_alert_prefs"
    private const val KEY_FULLSCREEN_DISABLED = "fullscreen_disabled"

    fun isFullScreenDisabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_FULLSCREEN_DISABLED, false)

    fun setFullScreenDisabled(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_FULLSCREEN_DISABLED, disabled) }
    }
}
