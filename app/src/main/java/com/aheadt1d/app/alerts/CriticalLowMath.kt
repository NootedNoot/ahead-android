package com.aheadt1d.app.alerts

/**
 * Pure trigger/recovery logic for the critical-low emergency siren
 * (CriticalLowSiren) - deliberately a hard value floor, tighter than the
 * general red floor (60) everywhere else in the app, and deliberately NOT
 * routed through AlertCoordinator's severity/cooldown/suppression machinery.
 * The whole point of this tier is that it can never be silenced by a bug or
 * a suppression rule (like the low-recovery hush) in that normal path - it's
 * its own independent decision, checked directly against the raw value.
 */
object CriticalLowMath {
    const val DEFAULT_FLOOR = 55

    // Deliberately the same 70 mg/dL split used everywhere else as "back in
    // range" (AlertCoordinator.LOW_HIGH_SPLIT, RedAlertActivity) rather than
    // just clearing the 55 floor - stopping the siren the instant a reading
    // ticks up to 56 would let it flap on ordinary noise right at the
    // boundary. Requiring a real climb back to a genuinely safer value means
    // one clear, unambiguous recovery, not a wobble.
    const val RECOVERY_THRESHOLD = 70

    fun isCriticalLow(value: Int, floor: Int = DEFAULT_FLOOR): Boolean = value <= floor

    fun hasRecovered(value: Int, recoveryThreshold: Int = RECOVERY_THRESHOLD): Boolean = value >= recoveryThreshold
}
