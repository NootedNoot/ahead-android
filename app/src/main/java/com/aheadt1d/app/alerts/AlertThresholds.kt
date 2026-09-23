package com.aheadt1d.app.alerts

import org.aheadt1d.ratemath.SeverityEngine

/**
 * The single shared "which side of the low/high split is this reading on"
 * decision, used by both AlertCoordinator (to decide what fires) and
 * AlertNotifier (to decide what a fired alert sounds/looks like). Extracted
 * 2026-08-26 - these two previously carried independent, hand-copied
 * implementations of the exact same formula with no compiler-enforced link
 * between them.
 *
 * 2026-08-28: LOW_HIGH_SPLIT now reads from SeverityEngine.DEFAULT_RED_LOW
 * (ahead-rate-math) instead of redeclaring its own literal 70 - found during
 * a fragmentation audit that this value, SeverityEngine's own red-low floor,
 * and several other independently-declared "70"s across this app and
 * ahead-backend were all meant to be the same number but had no
 * compiler-enforced link. SeverityEngine is the actual live severity decider
 * now (see GlucoseDisplayState.toDisplayState), so it's the natural single
 * source of truth for this app's Kotlin side.
 *
 * Considers the 15-min projection, not just the current value: a reading
 * that reads high (e.g. 79) can already be scored red because it's
 * projected to crash through the low band within 15 min (fast negative
 * rate). Classifying that by raw value alone would route it down the
 * high-side path - and, historically, would schedule the wrong emergency
 * alert type, texting a contact that the person is HIGH while they're
 * actually crashing low (real bug, fixed 2026-08-01).
 */
val LOW_HIGH_SPLIT = SeverityEngine.DEFAULT_RED_LOW

/**
 * 2026-09-20: also treats any value at or under the yellow-low band (80) as low-side, not just
 * the 70 split. Two reasons, one theoretical and one measured.
 *
 * Theoretical: a red alert at a value of 71-80 can only ever BE a low-side episode. Reaching
 * red-high from 80 inside 15 minutes would take roughly +11 mg/dL/min, which is not physiology.
 *
 * Measured: SeverityEngine.classify's turn-correction rewrites the 15-min projection, and that
 * rewritten number is what gets passed here. A red at 71 mg/dL whose projection was corrected to
 * 101 evaluated as HIGH side, which routed a genuine low into AlertCoordinator's high-side branch
 * - a 45-minute re-alert cooldown instead of 15, no low clear-hysteresis latch, the rolling 90-min
 * high correction grace instead of the fixed 30-min low one, and no "recovery stalled" re-fire.
 */
fun isLowSide(value: Int, projected: Int?): Boolean =
    value <= SeverityEngine.DEFAULT_YELLOW_LOW ||
        value <= LOW_HIGH_SPLIT ||
        (projected != null && projected <= LOW_HIGH_SPLIT)

/**
 * Midpoint for yellow-tier alerts (roughly the middle of the 70-180 target range).
 * Used by AlertCoordinator and AlertNotifier to cleanly distinguish yellow-low caution
 * (e.g. 71-80 mg/dL or falling toward 75) from yellow-high caution (e.g. >= 180 or climbing).
 */
const val YELLOW_MID_POINT = 125

fun isLowSideYellow(value: Int, projected: Int?): Boolean =
    (projected ?: value) < YELLOW_MID_POINT

/**
 * The four low-side alert phases (2026-09-23, the "value vs. rate" ticket) - decouples the
 * low/not-low LABEL from URGENCY, per the owner's explicit ask after a real false "Still low:
 * 79 mg/dL, rising" alert (79 is above his own 70 mg/dL threshold, so it should never have been
 * called "low" at all). See AlertCoordinator.updateLowPhase for how a reading is scored into one
 * of these; AlertNotifier/SpokenAlertText turn each into copy.
 *
 *  - URGENT: still low (raw value, or projection, under the 70 split), sustained negative rate -
 *    two or more CONSECUTIVE readings at/below SeverityEngine.RATE_FALLING_TRIGGER, not one
 *    blip. Needs action now.
 *  - STANDARD: still low, flat or mild negative rate - a real ongoing low, but not worsening
 *    enough (or not sustained enough) to escalate the tone over noise.
 *  - RISING: still low, but rate is positive and holding - informational, softer tone ("low but
 *    rising"), not a full alarm. Deliberately calmer so the person isn't prompted to over-treat
 *    (stack a second dose of fast carbs) a low that's already turning around.
 *  - RECOVERING: value (and projection) has crossed back to/above the 70 split, but hasn't held
 *    or kept climbing for LOW_STABILITY_READINGS_REQUIRED consecutive readings yet. Never
 *    "still low" - a reading that's actually >= 70 is not low by the app's own threshold, full
 *    stop - but also not yet declared fully resolved, since one good reading can be a bounce
 *    mid-drop rather than genuine recovery (real case: 79 -> 87 -> 89 -> 83 @ -1.2 mg/dL/min).
 */
enum class LowAlertPhase { URGENT, STANDARD, RISING, RECOVERING }

