package com.aheadt1d.app.alerts

/**
 * Pure classification for the correction-not-responding check (Gap 2) - no
 * Context, no I/O. Deliberately doesn't guess *why* a correction hasn't
 * worked yet - it only answers whether the expected response has shown up
 * by the end of the tracking window.
 */
object CorrectionResponseMath {
    enum class Outcome { WINDOW_OPEN, RESPONDING_OR_RESOLVED, NOT_RESPONDING }

    /**
     * [currentValue]/[currentRatePerMinute] null means no current reading is
     * available - treated as RESPONDING_OR_RESOLVED rather than guessing,
     * since firing an alert off missing data would be worse than staying
     * quiet for one cycle (the next cycle will re-evaluate with fresh data).
     */
    fun evaluate(
        correctionLoggedAt: Long,
        now: Long,
        windowMinutes: Long,
        currentValue: Int?,
        currentRatePerMinute: Double?,
        highThreshold: Int,
        responseRateThreshold: Double,
    ): Outcome {
        val elapsedMinutes = (now - correctionLoggedAt) / 60_000L
        if (elapsedMinutes < windowMinutes) return Outcome.WINDOW_OPEN

        if (currentValue == null || currentValue < highThreshold) return Outcome.RESPONDING_OR_RESOLVED
        // Meaningfully negative = at or below the (negative) threshold, e.g.
        // -1.0 mg/dL/min - already trending down counts as responding even
        // if the value hasn't dropped under highThreshold yet.
        if (currentRatePerMinute != null && currentRatePerMinute <= responseRateThreshold) return Outcome.RESPONDING_OR_RESOLVED

        return Outcome.NOT_RESPONDING
    }
}
