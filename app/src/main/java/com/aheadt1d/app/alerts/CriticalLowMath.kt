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

    // Nothing below this counts as resolved. Deliberately the same 75 mg/dL
    // AlertCoordinator.LOW_RED_CLEAR_HYSTERESIS already uses as "climbed
    // solidly past the danger band, not a one-reading wobble" - so the siren
    // and the ordinary red alert now agree on when a low is actually over.
    //
    // 2026-08-01: raised from 70. It HAD to move once the alert ladder below
    // gained a 73 rung: check() tests hasRecovered() first, so with a 70
    // threshold every value at or above 70 was classified "recovered" and the
    // 73 rung could never have fired at all. Raising it also matches the
    // stated intent of the ladder - keep pinging until the person is out of
    // the danger zone, not merely out of the lowest rung.
    const val RECOVERY_THRESHOLD = 75

    // 2026-08-01: added after a real missed-low episode plus a direct ask
    // from the person this app is for - someone with hypoglycemia
    // unawareness gets no adrenaline/sweat/shakes to warn them below ~55,
    // so by the time a value alone crosses DEFAULT_FLOOR the useful reaction
    // window is already shrinking. A value that's still above the hard
    // floor but genuinely falling (not CGM noise) deserves the same
    // can't-miss delivery as a true critical low, just not the same
    // nonstop-repeat urgency - see CriticalLowSiren's two-band doc.
    // -1.0 mg/dL/min was chosen as "confirmed declining", not a specific
    // crash-rate target - CriticalLowSiren's tanking heartbeat (not this
    // trigger) is what carries the "how urgent" judgment.
    const val TANKING_RATE_THRESHOLD_MG_DL_PER_MIN = -1.0

    /**
     * Descending alert ladder for the tanking band, in mg/dL. Every value
     * here is blood glucose - nothing in this file reads device battery.
     *
     * The shape is borrowed from stepped low-battery warnings, which is the
     * owner's own framing for what they wanted: a phone doesn't warn once at
     * 20% and then go quiet until it dies, it pings again at 10, at 5, at 1,
     * because each step is a materially worse situation than the last and the
     * person may have missed the earlier one. Same reasoning here - one alert
     * on the way down is a single point of failure, and the person this app
     * is for has hypoglycemia unawareness, so a missed ping isn't backed up
     * by their body noticing.
     *
     * Each rung fires at most once per episode, on the way DOWN only (see
     * [deepestRungCrossed]) - climbing back through them is recovery and
     * must stay quiet. Below [DEFAULT_FLOOR] the ladder stops mattering:
     * that's the emergency band, which repeats continuously on its own.
     */
    val TANKING_RUNGS = intArrayOf(73, 70, 67, 63)

    /** Top of the ladder - the highest value that can open a tanking episode. */
    const val TANKING_ENTRY = 73

    fun isCriticalLow(value: Int, floor: Int = DEFAULT_FLOOR): Boolean = value <= floor

    fun hasRecovered(value: Int, recoveryThreshold: Int = RECOVERY_THRESHOLD): Boolean = value >= recoveryThreshold

    /** True for a value that isn't critical yet but is on its way there -
     *  above the hard floor, at or under [TANKING_ENTRY], and falling at
     *  least [rateThreshold]. A flat or rising value in the same band (a
     *  routine, easily-handled lower-side reading) is deliberately NOT
     *  tanking: only a confirmed decline opens an episode. Once an episode
     *  IS open, the ladder takes over and rate no longer gates the rungs -
     *  crossing 63 matters whether or not that particular reading happened
     *  to look like a decline. */
    fun isTanking(
        value: Int,
        rate: Double?,
        floor: Int = DEFAULT_FLOOR,
        entry: Int = TANKING_ENTRY,
        rateThreshold: Double = TANKING_RATE_THRESHOLD_MG_DL_PER_MIN,
    ): Boolean = value > floor && value <= entry && rate != null && rate <= rateThreshold

    /**
     * The deepest ladder rung [value] has reached, or null if it's still
     * above the whole ladder. Lower return value = worse. Callers fire a new
     * ping only when this drops below the deepest rung already announced this
     * episode, which is what makes each rung fire once, downward only.
     */
    fun deepestRungCrossed(value: Int, rungs: IntArray = TANKING_RUNGS): Int? =
        rungs.filter { value <= it }.minOrNull()
}
