package com.aheadt1d.app.setup

import android.content.Context
import androidx.core.content.edit

/**
 * First-launch setup state. MainActivity checks isComplete() and bounces to
 * the wizard when it's false; the wizard sets it true on finish (or skip).
 *
 * cgm_path started out tailoring only the wizard's copy, but LatestTrendStore's
 * staleThresholdMinutes() now also branches on it (Dexcom gets a tighter
 * staleness cutoff than Juggluco/unsure, which have looser normal sync gaps) -
 * 2026-07-28: a real user's path was recorded as "juggluco" from early setup
 * testing despite running actual Dexcom apps, silently landing them on the
 * looser default threshold with no in-app way to notice or correct it short of
 * resetting the whole wizard. There's currently no settings screen to change
 * this after setup - only DebugMenuActivity's "Reset setup wizard".
 */
object SetupPrefs {
    const val PATH_DEXCOM = "dexcom"
    const val PATH_JUGGLUCO = "juggluco"
    const val PATH_UNSURE = "unsure"
    // AheadBLE reads the G7 transmitter directly over BLE and writes each
    // reading to Health Connect immediately, no app-level batching - a
    // different (tighter) latency profile than either of the above, so it
    // gets its own path/threshold rather than overloading "dexcom" (implies
    // the stock Dexcom app) or "unsure"/"juggluco" (looser gap tolerance).
    const val PATH_AHEADBLE = "aheadble"

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
