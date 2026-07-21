package com.aheadt1d.app.notifications

/**
 * Dexcom-style trend arrow derived from rate-of-change (mg/dL/min).
 * NotificationIconFactory renders these by rotating (and, for the double
 * variants, stacking) a single chevron drawable — rotationDegrees assumes
 * the source drawable points up at 0°, so 45° = upper-right, 90° = right,
 * 135° = lower-right, 180° = down.
 */
enum class GlucoseTrendArrow(val rotationDegrees: Float, val isDouble: Boolean, val label: String) {
    DOUBLE_UP(0f, true, "⇈"),
    UP(0f, false, "↑"),
    SLOWLY_RISING(45f, false, "↗"),
    FLAT(90f, false, "→"),
    SLOWLY_FALLING(135f, false, "↘"),
    DOWN(180f, false, "↓"),
    DOUBLE_DOWN(180f, true, "⇊");

    companion object {
        // Boundaries are inclusive on both sides of each band so a rate of
        // exactly ±1.0, ±2.0, or ±3.0 always maps to a determinate arrow
        // rather than depending on floating-point epsilon.
        fun fromRatePerMinute(rate: Double?): GlucoseTrendArrow = when {
            rate == null  -> FLAT
            rate >= 3.0   -> DOUBLE_UP
            rate >= 2.0   -> UP
            rate >= 1.0   -> SLOWLY_RISING
            rate >= -1.0  -> FLAT          // flat zone: -1.0 ≤ rate < 1.0
            rate >= -2.0  -> SLOWLY_FALLING
            rate >= -3.0  -> DOWN
            else          -> DOUBLE_DOWN
        }
    }
}
