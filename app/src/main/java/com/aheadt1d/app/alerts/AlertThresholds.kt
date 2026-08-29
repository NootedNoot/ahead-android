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

fun isLowSide(value: Int, projected: Int?): Boolean =
    value <= LOW_HIGH_SPLIT || (projected != null && projected <= LOW_HIGH_SPLIT)
