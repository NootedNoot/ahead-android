package com.aheadt1d.app.alerts

import java.util.Locale
import kotlin.math.abs

/**
 * Everything Ahead SAYS out loud for a glucose alert (red / yellow). Pure text - no Android
 * dependencies - so the wording lives in one place and is unit-tested.
 *
 * Kept short on purpose: it's spoken into someone's ear, often over music, and should sound like a
 * person talking, not an advert. The shape is
 * "<tier>. Glucose is at <value> and <direction> at <sign> <rate> points a minute. Projected <n> in <window>."
 * e.g. "Glucose is at 99 and falling fast at minus 2.2 points a minute. Projected 70 in fifteen minutes."
 *
 * 2026-09-20, at the owner's request: added the signed rate of change after the direction, worded the
 * way he'd say it ("minus 2.2 points a minute") rather than reading out units.
 */
object SpokenAlertText {

    /** Spoken-word direction (no "↓" glyphs), with an urgency cue for steep moves. */
    fun direction(rate: Double?): String = when {
        rate == null -> "trend unknown"
        rate <= -2.0 -> "and falling fast"
        rate < 0 -> "and falling"
        rate >= 2.0 -> "and rising fast"
        rate > 0 -> "and rising"
        else -> "and holding steady"
    }

    /**
     * The signed rate, ready to append right after [direction]: " at minus 2.8 points a minute" or
     * " at plus 1.4 points a minute". Empty when there is no usable rate or it rounds to 0.0 (nothing
     * worth saying).
     */
    fun rate(ratePerMinute: Double?): String {
        if (ratePerMinute == null || !ratePerMinute.isFinite()) return ""
        val magnitude = String.format(Locale.US, "%.1f", abs(ratePerMinute))
        if (magnitude == "0.0") return ""
        return " at ${if (ratePerMinute < 0) "minus" else "plus"} $magnitude points a minute"
    }

    /** Direction plus rate as one phrase, ready to follow the glucose number. */
    fun trend(ratePerMinute: Double?): String =
        if (ratePerMinute == null) ", trend unknown" else " ${direction(ratePerMinute)}${rate(ratePerMinute)}"

    fun projection(currentValue: Int, projected: Int?, projectedExtended: Int? = null): String {
        val (window, value) = AlertExplainer.pickProjectionWindow(currentValue, projected, projectedExtended)
        if (value == null) return ""
        val minWord = if (window == 30) "thirty" else "fifteen"
        return "Projected $value in $minWord minutes."
    }

    fun red(value: Int, rate: Double?, projected: Int?, projectedExtended: Int?, recovering: Boolean): String =
        if (recovering) {
            "Still low at $value, but rising${rate(rate)}. ${projection(value, projected, projectedExtended)} Keep monitoring."
        } else {
            "Urgent. Glucose is at $value${trend(rate)}. ${projection(value, projected, projectedExtended)} Check now."
        }

    fun yellow(value: Int, rate: Double?, projected: Int?, projectedExtended: Int?): String =
        "Heads up. Glucose is at $value${trend(rate)}. ${projection(value, projected, projectedExtended)}"
}
