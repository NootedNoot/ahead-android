package com.aheadt1d.app.tuning

import android.content.Context
import androidx.core.content.edit
import org.aheadt1d.ratemath.SeverityEngine

/** Debug tuning persisted on the phone and attached only to debug backend checks. */
data class TuningParameters(
    val yellowProjectedLow: Int = DEFAULT_YELLOW_LOW,
    val yellowProjectedHigh: Int = DEFAULT_YELLOW_HIGH,
    val redProjectedLow: Int = DEFAULT_RED_LOW,
    val redProjectedHigh: Int = DEFAULT_RED_HIGH,
    val extendedProjectionMinutes: Int = DEFAULT_EXTENDED_MINUTES,
    val smoothingIntervals: Int = DEFAULT_SMOOTHING_INTERVALS,
) {
    fun normalized(): TuningParameters {
        val redLow = redProjectedLow.coerceIn(40, 150)
        val redHigh = redProjectedHigh.coerceIn(150, 400)
        return copy(
            yellowProjectedLow = yellowProjectedLow.coerceIn(redLow, 180),
            yellowProjectedHigh = yellowProjectedHigh.coerceIn(120, redHigh),
            redProjectedLow = redLow,
            redProjectedHigh = redHigh,
            extendedProjectionMinutes = extendedProjectionMinutes.coerceIn(15, 60),
            smoothingIntervals = smoothingIntervals.coerceIn(1, 2),
        )
    }
}

object TuningPrefs {
    private const val PREFS_NAME = "ahead_debug_tuning"
    private const val KEY_YELLOW_LOW = "yellow_projected_low"
    private const val KEY_YELLOW_HIGH = "yellow_projected_high"
    private const val KEY_RED_LOW = "red_projected_low"
    private const val KEY_RED_HIGH = "red_projected_high"
    private const val KEY_EXTENDED_MINUTES = "extended_projection_minutes"
    private const val KEY_SMOOTHING_INTERVALS = "smoothing_intervals"

    fun load(context: Context): TuningParameters = TuningParameters(
        yellowProjectedLow = prefs(context).getInt(KEY_YELLOW_LOW, DEFAULT_YELLOW_LOW),
        yellowProjectedHigh = prefs(context).getInt(KEY_YELLOW_HIGH, DEFAULT_YELLOW_HIGH),
        redProjectedLow = prefs(context).getInt(KEY_RED_LOW, DEFAULT_RED_LOW),
        redProjectedHigh = prefs(context).getInt(KEY_RED_HIGH, DEFAULT_RED_HIGH),
        extendedProjectionMinutes = prefs(context).getInt(KEY_EXTENDED_MINUTES, DEFAULT_EXTENDED_MINUTES),
        smoothingIntervals = prefs(context).getInt(KEY_SMOOTHING_INTERVALS, DEFAULT_SMOOTHING_INTERVALS),
    ).normalized()

    fun save(context: Context, parameters: TuningParameters) {
        val value = parameters.normalized()
        prefs(context).edit {
            putInt(KEY_YELLOW_LOW, value.yellowProjectedLow)
            putInt(KEY_YELLOW_HIGH, value.yellowProjectedHigh)
            putInt(KEY_RED_LOW, value.redProjectedLow)
            putInt(KEY_RED_HIGH, value.redProjectedHigh)
            putInt(KEY_EXTENDED_MINUTES, value.extendedProjectionMinutes)
            putInt(KEY_SMOOTHING_INTERVALS, value.smoothingIntervals)
        }
    }

    fun reset(context: Context) = prefs(context).edit { clear() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

// 2026-08-11: this was 90, one of three hand-synced copies of this
// threshold that missed a sync when the backend/GlucoseStatusService copies
// were lowered to 80 on 2026-08-08 - a stale copy here silently
// reintroduced the original flat-90s over-alerting the 80 default was meant
// to fix. That incident is exactly why, 2026-08-28, these four now read
// from SeverityEngine (ahead-rate-math) instead of redeclaring their own
// literals - a fragmentation audit found SIX independent copies of these
// same numbers across this app and ahead-backend, one of which (a
// different one, SeverityEngine's own pre-fix value) had ALREADY silently
// drifted again (260 vs the correct 250). One source of truth now; these
// still exist as separate constants only so debug tuning's coerceIn bounds
// and this file's own doc/call sites don't need touching.
val DEFAULT_YELLOW_LOW = SeverityEngine.DEFAULT_YELLOW_LOW
val DEFAULT_YELLOW_HIGH = SeverityEngine.DEFAULT_YELLOW_HIGH
val DEFAULT_RED_LOW = SeverityEngine.DEFAULT_RED_LOW
val DEFAULT_RED_HIGH = SeverityEngine.DEFAULT_RED_HIGH
const val DEFAULT_EXTENDED_MINUTES = 30
const val DEFAULT_SMOOTHING_INTERVALS = 2
