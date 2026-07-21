package com.aheadt1d.app.tuning

import android.content.Context
import androidx.core.content.edit

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

const val DEFAULT_YELLOW_LOW = 90
const val DEFAULT_YELLOW_HIGH = 200
const val DEFAULT_RED_LOW = 70
const val DEFAULT_RED_HIGH = 250
const val DEFAULT_EXTENDED_MINUTES = 30
const val DEFAULT_SMOOTHING_INTERVALS = 2
