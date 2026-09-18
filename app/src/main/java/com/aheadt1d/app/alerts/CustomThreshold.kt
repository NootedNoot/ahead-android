package com.aheadt1d.app.alerts

import java.util.Locale

/**
 * A user-defined tripwire, completely separate from the severity engine's
 * yellow/red bands (AlertCoordinator/SeverityEngine) - Ryan's own situational
 * limits ("ping me if I ever hit 300", "watch for a fast drop today"), not a
 * replacement for the standard system. See [CustomThresholdMath] for the pure
 * crossing/escalation logic and [CustomThresholdCoordinator] for how/when
 * this actually posts a notification.
 *
 * [currentlyCrossed]/[lastFiredAtMs]/[lastFiredAtMetric] are persisted state,
 * not configuration - CustomThresholdCoordinator updates them every check
 * cycle so a fresh crossing can be told apart from "still crossed, already
 * pinged for this" across process restarts.
 */
data class CustomThreshold(
    val id: String,
    val kind: Kind,
    val direction: Direction,
    // mg/dL for VALUE, mg/dL/min for RATE.
    val amount: Double,
    val label: String,
    val temporary: Boolean,
    // null for a persistent threshold; a wall-clock cutoff for a temporary
    // "watch for this today" one. CustomThresholdCoordinator purges expired
    // temporary thresholds instead of just skipping them, so the list
    // doesn't quietly accumulate dead entries.
    val expiresAtMs: Long? = null,
    val enabled: Boolean = true,
    val currentlyCrossed: Boolean = false,
    val lastFiredAtMs: Long? = null,
    val lastFiredAtMetric: Double? = null,
) {
    enum class Kind { VALUE, RATE }

    /** RISING = alert at-or-above [amount] (climbing toward/through it).
     *  FALLING = alert at-or-below [amount] (dropping toward/through it).
     *  Applies to both Kind.VALUE ("climbing toward 300") and Kind.RATE
     *  ("rate hits +5.0 or steeper" vs "-5.0 or steeper"). */
    enum class Direction { RISING, FALLING }

    /** Plain-language description for a threshold the user didn't give
     *  their own label. */
    fun autoLabel(): String = when (kind) {
        Kind.VALUE -> if (direction == Direction.RISING) "Glucose ≥ ${amount.toInt()} mg/dL" else "Glucose ≤ ${amount.toInt()} mg/dL"
        Kind.RATE -> if (direction == Direction.RISING) "Rate ≥ +${"%.1f".format(Locale.US, amount)}/min" else "Rate ≤ ${"%.1f".format(Locale.US, amount)}/min"
    }

    fun displayLabel(): String = label.ifBlank { autoLabel() }
}
