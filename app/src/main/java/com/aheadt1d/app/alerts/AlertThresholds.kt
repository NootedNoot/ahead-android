package com.aheadt1d.app.alerts

/**
 * The single shared "which side of the low/high split is this reading on"
 * decision, used by both AlertCoordinator (to decide what fires) and
 * AlertNotifier (to decide what a fired alert sounds/looks like). Extracted
 * 2026-08-26 - these two previously carried independent, hand-copied
 * implementations of the exact same formula with no compiler-enforced link
 * between them.
 *
 * Considers the 15-min projection, not just the current value: a reading
 * that reads high (e.g. 79) can already be scored red because it's
 * projected to crash through the low band within 15 min (fast negative
 * rate). Classifying that by raw value alone would route it down the
 * high-side path - and, historically, would schedule the wrong emergency
 * alert type, texting a contact that the person is HIGH while they're
 * actually crashing low (real bug, fixed 2026-08-01).
 */
const val LOW_HIGH_SPLIT = 70

fun isLowSide(value: Int, projected: Int?): Boolean =
    value <= LOW_HIGH_SPLIT || (projected != null && projected <= LOW_HIGH_SPLIT)
