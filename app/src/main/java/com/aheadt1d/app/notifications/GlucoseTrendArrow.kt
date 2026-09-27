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
        // Unified Dexcom-compatible arrow thresholds:
        // Flat: -0.5 to +0.5 mg/dL/min
        // 45 deg: +-0.5 to +-1.5 mg/dL/min
        // Single arrow: +-1.5 to +-3.0 mg/dL/min
        // Double arrow: > +-3.0 mg/dL/min
        fun fromRatePerMinute(rate: Double?): GlucoseTrendArrow = when {
            rate == null -> FLAT
            rate >= 3.0  -> DOUBLE_UP
            rate >= 1.5  -> UP
            rate >= 0.5  -> SLOWLY_RISING
            rate > -0.5  -> FLAT
            rate > -1.5  -> SLOWLY_FALLING
            rate > -3.0  -> DOWN
            else         -> DOUBLE_DOWN
        }
    }
}
