package com.aheadt1d.app.alerts

import com.aheadt1d.app.health.GlucosePoint
import java.time.Duration

/**
 * Pure duration/tier math for the sustained-high-plateau check (Gap 1) - no
 * Context, no I/O, so it's plain-JUnit testable and reusable identically by
 * both PlateauCoordinator (real firing) and the tuning menu's live preview
 * (read-only, never fires anything).
 */
object PlateauMath {
    /**
     * Minutes of the longest contiguous run of readings >= [threshold]
     * ending at the most recent point, or null if the latest reading itself
     * is below threshold (no active plateau). A gap between two consecutive
     * readings wider than [maxGapMinutes] breaks contiguity - a CGM dropout
     * can't be silently treated as "still plateaued" through the hole.
     */
    fun currentPlateauDurationMinutes(
        points: List<GlucosePoint>,
        threshold: Int,
        maxGapMinutes: Long = 20,
    ): Long? {
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.time }
        val latest = sorted.last()
        if (latest.sgv < threshold) return null

        val maxGap = Duration.ofMinutes(maxGapMinutes)
        var start = latest.time
        // Walk backward one confirmed in-streak point at a time: `current`
        // going into each iteration has already passed both checks (either
        // it's `latest`, checked above, or it was `prev` in the prior
        // successful iteration) - so only `prev` needs checking here.
        for (i in sorted.lastIndex downTo 1) {
            val current = sorted[i]
            val prev = sorted[i - 1]
            if (prev.sgv < threshold || Duration.between(prev.time, current.time) > maxGap) break
            start = prev.time
        }
        return Duration.between(start, latest.time).toMinutes()
    }

    /** 0 = not yet a plateau, 1 = fired at [highDurationMinutes], 2+ = one
     *  tier higher per additional [escalationStepMinutes] beyond that. */
    fun tierFor(durationMinutes: Long, highDurationMinutes: Long, escalationStepMinutes: Long): Int {
        if (durationMinutes < highDurationMinutes) return 0
        val step = escalationStepMinutes.coerceAtLeast(1)
        val over = durationMinutes - highDurationMinutes
        return 1 + (over / step).toInt()
    }

    /** True once the most recent reading alone has fallen below
     *  threshold-hysteresisBuffer - the signal to clear an active plateau
     *  episode. Uses only the latest point (not a duration), so it reacts
     *  immediately rather than waiting for a sustained drop. */
    fun hasDroppedBelowHysteresisFloor(points: List<GlucosePoint>, threshold: Int, hysteresisBuffer: Int): Boolean {
        val latest = points.maxByOrNull { it.time } ?: return false
        return latest.sgv < threshold - hysteresisBuffer
    }
}
