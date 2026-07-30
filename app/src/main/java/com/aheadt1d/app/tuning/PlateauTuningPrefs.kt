package com.aheadt1d.app.tuning

import android.content.Context
import androidx.core.content.edit

/**
 * Debug tuning for the sustained-high-plateau (Gap 1) and correction-response
 * (Gap 2) checks - persisted separately from [TuningParameters] on purpose.
 * TuningParameters is literally serialized into GlucoseCheckWorker's backend
 * POST body; keeping these Android-only constants in their own struct/prefs
 * file makes it structurally obvious they can never accidentally leak into
 * that payload (this feature never talks to the backend at all).
 */
data class PlateauTuningParameters(
    val highThreshold: Int = DEFAULT_HIGH_THRESHOLD,
    val highDurationMinutes: Int = DEFAULT_HIGH_DURATION_MIN,
    val hysteresisBuffer: Int = DEFAULT_HYSTERESIS_BUFFER,
    val escalationStepMinutes: Int = DEFAULT_ESCALATION_STEP_MIN,
    val cooldownMinutes: Int = DEFAULT_COOLDOWN_MIN,
    val correctionWindowMinutes: Int = DEFAULT_CORRECTION_WINDOW_MIN,
    val correctionResponseRateThreshold: Double = DEFAULT_RESPONSE_RATE_THRESHOLD,
    // Low-side correction-response (Gap 2, low direction) - deliberately its
    // own trio rather than reusing the high-side fields above: fast carbs
    // reverse a low far quicker than insulin reverses a high, so the window
    // is much shorter and the rate check is a positive (rising) bar instead
    // of a negative (falling) one. lowThreshold defaults to the same 70 mg/dL
    // AlertCoordinator.LOW_HIGH_SPLIT already uses for "this is a low" -
    // kept as its own independent constant (not imported) for the same
    // decoupling reason highThreshold above is independent of the backend's
    // severity classification.
    val lowThreshold: Int = DEFAULT_LOW_THRESHOLD,
    val lowCorrectionWindowMinutes: Int = DEFAULT_LOW_CORRECTION_WINDOW_MIN,
    val lowResponseRateThreshold: Double = DEFAULT_LOW_RESPONSE_RATE_THRESHOLD,
) {
    fun normalized(): PlateauTuningParameters = copy(
        highThreshold = highThreshold.coerceIn(120, 400),
        highDurationMinutes = highDurationMinutes.coerceIn(15, 360),
        hysteresisBuffer = hysteresisBuffer.coerceIn(0, 100),
        escalationStepMinutes = escalationStepMinutes.coerceIn(15, 240),
        cooldownMinutes = cooldownMinutes.coerceIn(5, 240),
        correctionWindowMinutes = correctionWindowMinutes.coerceIn(15, 180),
        correctionResponseRateThreshold = correctionResponseRateThreshold.coerceIn(-10.0, -0.1),
        lowThreshold = lowThreshold.coerceIn(40, 90),
        lowCorrectionWindowMinutes = lowCorrectionWindowMinutes.coerceIn(10, 60),
        lowResponseRateThreshold = lowResponseRateThreshold.coerceIn(0.1, 5.0),
    )

    /** Derived, not persisted: how far back GlucoseCheckWorker needs to read
     *  Health Connect to guarantee enough history for the CURRENT tuning
     *  values - recomputed fresh every cycle, so live-adjusting
     *  highDurationMinutes in the debug menu immediately widens or narrows
     *  the read with no rebuild. Headroom covers a few escalation steps past
     *  the base duration so a long-running plateau's tier keeps climbing
     *  correctly instead of the read window itself becoming the limit. */
    fun lookbackMinutes(): Long =
        (highDurationMinutes.toLong() + escalationStepMinutes.toLong() * 3 + 30).coerceAtMost(24 * 60L)
}

object PlateauTuningPrefs {
    private const val PREFS_NAME = "ahead_plateau_tuning"
    private const val KEY_HIGH_THRESHOLD = "high_threshold"
    private const val KEY_HIGH_DURATION = "high_duration_minutes"
    private const val KEY_HYSTERESIS_BUFFER = "hysteresis_buffer"
    private const val KEY_ESCALATION_STEP = "plateau_escalation_step_minutes"
    private const val KEY_COOLDOWN = "plateau_cooldown_minutes"
    private const val KEY_CORRECTION_WINDOW = "correction_window_minutes"
    private const val KEY_RESPONSE_RATE_THRESHOLD = "correction_response_rate_threshold"
    private const val KEY_LOW_THRESHOLD = "low_threshold"
    private const val KEY_LOW_CORRECTION_WINDOW = "low_correction_window_minutes"
    private const val KEY_LOW_RESPONSE_RATE_THRESHOLD = "low_correction_response_rate_threshold"

    fun load(context: Context): PlateauTuningParameters = PlateauTuningParameters(
        highThreshold = prefs(context).getInt(KEY_HIGH_THRESHOLD, DEFAULT_HIGH_THRESHOLD),
        highDurationMinutes = prefs(context).getInt(KEY_HIGH_DURATION, DEFAULT_HIGH_DURATION_MIN),
        hysteresisBuffer = prefs(context).getInt(KEY_HYSTERESIS_BUFFER, DEFAULT_HYSTERESIS_BUFFER),
        escalationStepMinutes = prefs(context).getInt(KEY_ESCALATION_STEP, DEFAULT_ESCALATION_STEP_MIN),
        cooldownMinutes = prefs(context).getInt(KEY_COOLDOWN, DEFAULT_COOLDOWN_MIN),
        correctionWindowMinutes = prefs(context).getInt(KEY_CORRECTION_WINDOW, DEFAULT_CORRECTION_WINDOW_MIN),
        correctionResponseRateThreshold = prefs(context)
            .getFloat(KEY_RESPONSE_RATE_THRESHOLD, DEFAULT_RESPONSE_RATE_THRESHOLD.toFloat())
            .toDouble(),
        lowThreshold = prefs(context).getInt(KEY_LOW_THRESHOLD, DEFAULT_LOW_THRESHOLD),
        lowCorrectionWindowMinutes = prefs(context).getInt(KEY_LOW_CORRECTION_WINDOW, DEFAULT_LOW_CORRECTION_WINDOW_MIN),
        lowResponseRateThreshold = prefs(context)
            .getFloat(KEY_LOW_RESPONSE_RATE_THRESHOLD, DEFAULT_LOW_RESPONSE_RATE_THRESHOLD.toFloat())
            .toDouble(),
    ).normalized()

    fun save(context: Context, parameters: PlateauTuningParameters) {
        val value = parameters.normalized()
        prefs(context).edit {
            putInt(KEY_HIGH_THRESHOLD, value.highThreshold)
            putInt(KEY_HIGH_DURATION, value.highDurationMinutes)
            putInt(KEY_HYSTERESIS_BUFFER, value.hysteresisBuffer)
            putInt(KEY_ESCALATION_STEP, value.escalationStepMinutes)
            putInt(KEY_COOLDOWN, value.cooldownMinutes)
            putInt(KEY_CORRECTION_WINDOW, value.correctionWindowMinutes)
            putFloat(KEY_RESPONSE_RATE_THRESHOLD, value.correctionResponseRateThreshold.toFloat())
            putInt(KEY_LOW_THRESHOLD, value.lowThreshold)
            putInt(KEY_LOW_CORRECTION_WINDOW, value.lowCorrectionWindowMinutes)
            putFloat(KEY_LOW_RESPONSE_RATE_THRESHOLD, value.lowResponseRateThreshold.toFloat())
        }
    }

    fun reset(context: Context) = prefs(context).edit { clear() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

const val DEFAULT_HIGH_THRESHOLD = 250
const val DEFAULT_HIGH_DURATION_MIN = 90
const val DEFAULT_HYSTERESIS_BUFFER = 20
const val DEFAULT_ESCALATION_STEP_MIN = 60
const val DEFAULT_COOLDOWN_MIN = 60
const val DEFAULT_CORRECTION_WINDOW_MIN = 45
const val DEFAULT_RESPONSE_RATE_THRESHOLD = -1.0
const val DEFAULT_LOW_THRESHOLD = 70
const val DEFAULT_LOW_CORRECTION_WINDOW_MIN = 20
const val DEFAULT_LOW_RESPONSE_RATE_THRESHOLD = 1.0
