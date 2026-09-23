package com.aheadt1d.app.tuning

import android.content.Context
import androidx.core.content.edit
import org.aheadt1d.ratemath.TreatmentEffectWindow

/**
 * Tunable parameters for exercise-related alert and projection behavior.
 * Specifically controls the delayed-onset hypoglycemia risk window
 * ([exerciseRiskWindowHours]). Persisted separately from [TuningParameters]
 * and [PlateauTuningParameters] so exercise sensitivity tuning stays clean and modular.
 */
data class ExerciseTuningParameters(
    val exerciseRiskWindowHours: Int = DEFAULT_EXERCISE_RISK_WINDOW_HOURS,
) {
    fun normalized(): ExerciseTuningParameters = copy(
        exerciseRiskWindowHours = exerciseRiskWindowHours.coerceIn(2, 24),
    )

    companion object {
        const val DEFAULT_EXERCISE_RISK_WINDOW_HOURS: Int =
            TreatmentEffectWindow.EXERCISE_RISK_WINDOW_HOURS
    }
}

object ExerciseTuningPrefs {
    private const val PREFS_NAME = "ahead_exercise_tuning"
    private const val KEY_EXERCISE_RISK_WINDOW_HOURS = "exercise_risk_window_hours"

    fun load(context: Context): ExerciseTuningParameters = ExerciseTuningParameters(
        exerciseRiskWindowHours = prefs(context).getInt(
            KEY_EXERCISE_RISK_WINDOW_HOURS,
            ExerciseTuningParameters.DEFAULT_EXERCISE_RISK_WINDOW_HOURS
        ),
    ).normalized()

    fun save(context: Context, parameters: ExerciseTuningParameters) {
        val value = parameters.normalized()
        prefs(context).edit {
            putInt(KEY_EXERCISE_RISK_WINDOW_HOURS, value.exerciseRiskWindowHours)
        }
    }

    fun reset(context: Context) {
        prefs(context).edit { clear() }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
