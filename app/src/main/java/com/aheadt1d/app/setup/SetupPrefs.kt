package com.aheadt1d.app.setup

import android.content.Context
import androidx.core.content.edit

/**
 * First-launch setup state. MainActivity checks isComplete() and bounces to
 * the wizard when it's false; the wizard sets it true on finish (or skip).
 * cgm_path just tailors the wizard copy - nothing downstream depends on it.
 */
object SetupPrefs {
    const val PATH_DEXCOM = "dexcom"
    const val PATH_JUGGLUCO = "juggluco"
    const val PATH_UNSURE = "unsure"

    private const val PREFS_NAME = "ahead_setup"
    private const val KEY_COMPLETE = "setup_complete"
    private const val KEY_PATH = "cgm_path"

    fun isComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMPLETE, false)

    fun setComplete(context: Context, complete: Boolean) {
        prefs(context).edit { putBoolean(KEY_COMPLETE, complete) }
    }

    fun cgmPath(context: Context): String =
        prefs(context).getString(KEY_PATH, PATH_UNSURE) ?: PATH_UNSURE

    fun setCgmPath(context: Context, path: String) {
        prefs(context).edit { putString(KEY_PATH, path) }
    }

    /**
     * Clears ONLY first-run wizard state (the completion gate + the chosen CGM
     * path) so the wizard runs again. Everything else - chart prefs, alert
     * channel/state, the latest trend/reading - lives in other prefs files and
     * is untouched. Used by the debug-only reset trigger in MainActivity.
     */
    fun resetWizardState(context: Context) {
        prefs(context).edit {
            remove(KEY_COMPLETE)
            remove(KEY_PATH)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
