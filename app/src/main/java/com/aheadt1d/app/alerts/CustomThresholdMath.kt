package com.aheadt1d.app.alerts

/**
 * Pure crossing/escalation logic for [CustomThreshold], kept separate from
 * [CustomThresholdCoordinator] (which owns SharedPreferences I/O and actually
 * posts notifications) the same way PlateauMath/CorrectionResponseMath stay
 * separate from PlateauCoordinator - this half is what a unit test exercises
 * directly, with no Android dependency.
 *
 * The whole feature's design intent: a single override alert on a fresh
 * crossing, then back to respecting silent/DND mode normally, UNLESS the
 * value/rate moves meaningfully further past the threshold (a fresh escalation)
 * or it recovers and crosses again later - never a repeat ping for "still
 * crossed, nothing new to say," which is exactly the kind of repeat-siren
 * failure mode the 2026-08-20 removal of the old critical-low siren was
 * about. See CustomThresholdCoordinator's own doc for how [Outcome] maps onto
 * actually firing.
 */
object CustomThresholdMath {
    // Mirrors AlertCoordinator's own yellow re-alert-on-worsening constant
    // (re-alerts once a projection worsens by >=20 mg/dL) - reusing the same
    // number keeps "how much worse counts as worth another ping" consistent
    // with the rest of the app instead of inventing an unrelated one here.
    const val VALUE_ESCALATION_DELTA = 20.0

    // No existing app-wide precedent for a rate re-escalation step, so this
    // is a fresh, reasoned pick: 1.0 mg/dL/min is a clearly-noticeable
    // further step (SeverityEngine's own RATE_FALLING_TRIGGER/
    // RATE_RISING_TRIGGER are spaced about that far apart), not so small
    // that ordinary reading-to-reading noise re-fires it every cycle.
    const val RATE_ESCALATION_DELTA = 1.0

    enum class Outcome {
        /** Nothing worth telling anyone - either never crossed, or crossed
         *  and unchanged since the last fire. */
        NONE,
        /** Was clear, now crossed - the single override-silent-mode ping. */
        FRESH_CROSS,
        /** Still crossed, but moved meaningfully further past the threshold
         *  since the last fire - a second override ping, same as a fresh
         *  crossing, because it's genuinely new information. */
        ESCALATED,
        /** Was crossed, now isn't - no notification, just resets the state
         *  so a later re-crossing fires fresh instead of being mistaken for
         *  "still crossed." */
        RECOVERED,
    }

    data class Evaluation(val outcome: Outcome, val isCrossedNow: Boolean, val metric: Double?)

    /**
     * Compares [threshold]'s PERSISTED crossed-state to the current
     * [value]/[rate] (whichever [CustomThreshold.Kind] the threshold cares
     * about) and decides what's newsworthy. [rate] is meant to be the same
     * displayed rate the main screen and every notification already show
     * (GlucoseDisplayState.Reading.ratePerMinute) - not a smoothed/consensus
     * rate - so a rate threshold always fires off the number the user can
     * actually see, and can never disagree with it the way the projection
     * bug fixed in SeverityEngine.kt did.
     *
     * A RATE threshold with [rate] == null (no rate available yet) is never
     * considered crossed - missing data is not a trigger.
     */
    fun evaluate(threshold: CustomThreshold, value: Int, rate: Double?): Evaluation {
        val metric = when (threshold.kind) {
            CustomThreshold.Kind.VALUE -> value.toDouble()
            CustomThreshold.Kind.RATE -> rate
        }
        val nowCrossed = metric != null && when (threshold.direction) {
            CustomThreshold.Direction.RISING -> metric >= threshold.amount
            CustomThreshold.Direction.FALLING -> metric <= threshold.amount
        }

        if (!nowCrossed) {
            if (!threshold.currentlyCrossed) return Evaluation(Outcome.NONE, false, metric)
            // Hysteresis (2026-09-13): a metric sitting exactly at the
            // boundary (e.g. rate wobbling +5.0/+4.9 around a +5.0 threshold)
            // used to flip RECOVERED then FRESH_CROSS every other cycle -
            // two override-silence pings for one continuous episode, the
            // "flap" AlertCoordinator's own doc already calls out as a real
            // failure mode it guards against for the ordinary yellow/red
            // path. Mirrors that same idea here: only actually clear once
            // the metric has moved back past the threshold by a real buffer
            // (the same escalation delta used for the opposite direction),
            // not the instant it technically re-crosses zero-margin. A
            // missing metric (data genuinely went away) still clears
            // immediately - that's not noise, that's a real state change.
            if (metric == null) return Evaluation(Outcome.RECOVERED, false, null)
            val clearDelta = if (threshold.kind == CustomThreshold.Kind.VALUE) VALUE_ESCALATION_DELTA else RATE_ESCALATION_DELTA
            val trulyClear = when (threshold.direction) {
                CustomThreshold.Direction.RISING -> metric < threshold.amount - clearDelta
                CustomThreshold.Direction.FALLING -> metric > threshold.amount + clearDelta
            }
            return if (trulyClear) {
                Evaluation(Outcome.RECOVERED, false, metric)
            } else {
                Evaluation(Outcome.NONE, true, metric)
            }
        }
        if (!threshold.currentlyCrossed) {
            return Evaluation(Outcome.FRESH_CROSS, true, metric)
        }

        // Still crossed - only newsworthy again if it moved meaningfully
        // further past the threshold than where it last fired. metric is
        // guaranteed non-null here: nowCrossed (checked above) short-circuits
        // on `metric != null` before this point is ever reached.
        val currentMetric = metric ?: return Evaluation(Outcome.NONE, true, null)
        val delta = if (threshold.kind == CustomThreshold.Kind.VALUE) VALUE_ESCALATION_DELTA else RATE_ESCALATION_DELTA
        // A currentlyCrossed=true threshold with no recorded lastFiredAtMetric
        // (reachable via an older/corrupted persisted entry - see
        // CustomThresholdStore's malformed-entry handling) used to silently
        // compare the metric against itself here, meaning it could NEVER
        // escalate until an unrelated recovery reset it - a real, if rare,
        // way to permanently miss a genuine worsening. Re-sync by treating
        // an unknown last-fired point as "escalated now" instead: one extra
        // ping to re-establish a real baseline beats silently going quiet
        // forever.
        val lastMetric = threshold.lastFiredAtMetric
            ?: return Evaluation(Outcome.ESCALATED, true, currentMetric)
        val movedFurther = when (threshold.direction) {
            CustomThreshold.Direction.RISING -> currentMetric >= lastMetric + delta
            CustomThreshold.Direction.FALLING -> currentMetric <= lastMetric - delta
        }
        return Evaluation(if (movedFurther) Outcome.ESCALATED else Outcome.NONE, true, currentMetric)
    }
}
