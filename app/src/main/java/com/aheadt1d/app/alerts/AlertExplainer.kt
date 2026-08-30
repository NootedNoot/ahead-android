package com.aheadt1d.app.alerts

import org.aheadt1d.ratemath.SeverityEngine
import kotlin.math.abs

/**
 * Turns the numbers trend-detector.js/SeverityEngine already compute
 * (current value, rate, 15-min and 30-min projections) into one short,
 * plain-language sentence explaining WHY an alert fired - the "Alert
 * Transparency" feature. Read-only, display-layer only: nothing here
 * decides severity, changes a threshold, or influences timing - it only
 * describes a decision that was already made elsewhere (classifySeverity in
 * trend-detector.js, mirrored by SeverityEngine on this side). Safe to
 * change freely without any detection-logic review.
 *
 * [SeverityEngine.SEVERE_LOW_RED_FLOOR] is the one constant borrowed from
 * the real severity logic, not reimplemented - it's the same hard floor
 * AlertThresholds.kt already treats as the single source of truth (see that
 * file's own doc), so this can't silently drift from the actual threshold.
 */
object AlertExplainer {

    // Below this magnitude (mg/dL/min), the sentence says "slowly" instead
    // of a bare number - a rate like 0.3/min isn't a meaningful number to
    // read in a glance, but "rising slowly" still says something real.
    private const val SLOW_RATE_MAGNITUDE = 1.0

    // How many minutes ahead each projection window looks - mirrors
    // trend-detector.js's PROJECTION_MINUTES/EXTENDED_PROJECTION_MINUTES.
    // Not imported from there (this is Kotlin, that's JS, no shared
    // constant to import) - if those ever change, this drifts silently,
    // same class of "duplicated on purpose" tradeoff this codebase already
    // makes elsewhere (see AlertCoordinator's own doc on threshold
    // duplication) rather than wiring a cross-language constant just for
    // display copy.
    private const val NEAR_WINDOW_MINUTES = 15
    private const val EXTENDED_WINDOW_MINUTES = 30

    // How close the near-term projection has to sit to the current value
    // before it's treated as "not really moving" for window-choice purposes
    // (see pickProjectionWindow) - a few mg/dL of projected drift over 15
    // minutes isn't the number worth putting in a one-line explanation.
    private const val NEAR_TERM_UNREMARKABLE_DELTA = 5

    /**
     * The one-line explanation for a yellow or red alert. Example shapes
     * (see the feature's own spec):
     *  - "78 mg/dL, dropping 2.1/min → projected 61 in 15 min"
     *  - "192 mg/dL, rising slowly → projected 205 in 30 min"
     *  - "56 mg/dL — below critical threshold" (hard floor, no projection)
     *
     * The hard-floor case is checked first and independently of severity -
     * currentValue <= SEVERE_LOW_RED_FLOOR is RED unconditionally in the
     * real decision (classifySeverity's first check), and that's a flat
     * threshold, not a projection, so the copy must never imply one exists
     * for it (requirement: don't imply a projection when there isn't one).
     */
    fun oneLiner(currentValue: Int, rate: Double?, projected: Int?, projectedExtended: Int?): String {
        if (currentValue <= SeverityEngine.SEVERE_LOW_RED_FLOOR) {
            return "$currentValue mg/dL — below critical threshold"
        }
        if (rate == null) {
            return "$currentValue mg/dL — trend not available yet"
        }

        val (window, value) = pickProjectionWindow(currentValue, projected, projectedExtended)
        val direction = directionPhrase(rate)
        return if (value != null) {
            "$currentValue mg/dL, $direction → projected $value in $window min"
        } else {
            "$currentValue mg/dL, $direction"
        }
    }

    /** Same idea for the signal-lost case, which has no current
     *  value/rate/projection to explain (nothing's been confirmed since
     *  signal dropped) - explains what's known instead: how long it's been,
     *  and what the last real reading was. */
    fun signalLostOneLiner(lastValue: Int, ageMinutes: Long): String =
        "No new data for ${ageMinutes}m — last reading was $lastValue mg/dL"

    /**
     * The expanded "why am I seeing this" detail: the raw numbers behind
     * [oneLiner], for anyone who wants more than the one-liner. Deliberately
     * plain/technical (unlike oneLiner, which is meant to be skimmed) -
     * this is the "show your work" view, not another summary.
     */
    fun detailLine(currentValue: Int, rate: Double?, projected: Int?, projectedExtended: Int?): String {
        val rateStr = rate?.let { "${signedRate(it)} mg/dL/min" } ?: "unavailable"
        val nearStr = projected?.let { "$it mg/dL" } ?: "n/a"
        val extendedStr = projectedExtended?.let { "$it mg/dL" } ?: "n/a"
        return "Current: $currentValue mg/dL · Rate: $rateStr · " +
            "${NEAR_WINDOW_MINUTES}-min projection: $nearStr · ${EXTENDED_WINDOW_MINUTES}-min projection: $extendedStr"
    }

    /** "dropping 2.1/min" / "rising slowly" / "holding steady" - see
     *  SLOW_RATE_MAGNITUDE's own doc for why slow rates drop the number. */
    private fun directionPhrase(rate: Double): String {
        val magnitude = abs(rate)
        if (magnitude < 0.05) return "holding steady"
        val verb = if (rate < 0) "dropping" else "rising"
        return if (magnitude >= SLOW_RATE_MAGNITUDE) {
            "$verb ${"%.1f".format(magnitude)}/min"
        } else {
            "$verb slowly"
        }
    }

    private fun signedRate(rate: Double): String =
        if (rate > 0) "+${"%.1f".format(rate)}" else "%.1f".format(rate)

    /**
     * Which projection window to cite in the one-liner, and its value.
     * Prefers the near-term (15-min) window - it's the one that actually
     * decided most yellow/red tiers. Falls back to the extended (30-min)
     * window only when the near-term number is itself unremarkable (close
     * to the current value - see NEAR_TERM_UNREMARKABLE_DELTA) while the
     * extended one has moved meaningfully further in the same direction:
     * that's exactly the shape of trend-detector.js's own extended-horizon
     * yellow nudge (a slow-but-real move that hasn't reached danger in 15
     * minutes but would given more time) - see that file's
     * EXTENDED_PROJECTION_MINUTES doc. Matches the spec's own
     * "192 mg/dL, rising slowly → projected 205 in 30 min" example, where
     * the near-term number alone wouldn't be worth citing.
     */
    private fun pickProjectionWindow(currentValue: Int, projected: Int?, projectedExtended: Int?): Pair<Int, Int?> {
        if (projected == null) return EXTENDED_WINDOW_MINUTES to projectedExtended
        val nearMoveIsSmall = abs(projected - currentValue) < NEAR_TERM_UNREMARKABLE_DELTA
        val extendedMovesFurther = projectedExtended != null &&
            abs(projectedExtended - currentValue) > abs(projected - currentValue)
        return if (nearMoveIsSmall && extendedMovesFurther) {
            EXTENDED_WINDOW_MINUTES to projectedExtended
        } else {
            NEAR_WINDOW_MINUTES to projected
        }
    }
}
