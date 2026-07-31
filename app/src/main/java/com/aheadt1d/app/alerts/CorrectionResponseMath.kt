package com.aheadt1d.app.alerts

/**
 * Pure classification for the correction-not-responding check (Gap 2) - no
 * Context, no I/O. Deliberately doesn't guess *why* a correction hasn't
 * worked yet - it only answers whether the expected response has shown up
 * by the end of the tracking window.
 */
object CorrectionResponseMath {
    // INCONCLUSIVE is distinct from RESPONDING_OR_RESOLVED: the caller
    // (PlateauCoordinator) clears its tracking state on a real resolution,
    // but must keep tracking through INCONCLUSIVE, since a null reading here
    // usually just means a transient Health Connect read gap, not that the
    // correction actually worked - clearing state in that case would
    // silently and permanently drop the "not responding" check for this
    // episode the moment fresh data comes back.
    enum class Outcome { WINDOW_OPEN, RESPONDING_OR_RESOLVED, NOT_RESPONDING, INCONCLUSIVE }

    /**
     * [currentValue]/[currentRatePerMinute] null means no current reading is
     * available - never guessed as resolved (that would risk silently
     * dropping a real "not responding" alert on an ordinary sync hiccup);
     * returns INCONCLUSIVE so the caller keeps waiting for real data instead
     * of finalizing this episode one way or the other.
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

        if (currentValue == null) return Outcome.INCONCLUSIVE
        if (currentValue < highThreshold) return Outcome.RESPONDING_OR_RESOLVED
        // Meaningfully negative = at or below the (negative) threshold, e.g.
        // -1.0 mg/dL/min - already trending down counts as responding even
        // if the value hasn't dropped under highThreshold yet.
        if (currentRatePerMinute != null && currentRatePerMinute <= responseRateThreshold) return Outcome.RESPONDING_OR_RESOLVED

        return Outcome.NOT_RESPONDING
    }

    /**
     * Low-side counterpart to [evaluate] - fast carbs are expected to reverse
     * a low far quicker than insulin reverses a high, so this is checked
     * against its own (shorter) window and a positive rate threshold rather
     * than reusing the high-side constants. "Responding" means glucose has
     * climbed back above [lowThreshold], or is rising at least
     * [responseRateThreshold] mg/dL/min even before clearing it.
     */
    fun evaluateLow(
        correctionLoggedAt: Long,
        now: Long,
        windowMinutes: Long,
        currentValue: Int?,
        currentRatePerMinute: Double?,
        lowThreshold: Int,
        responseRateThreshold: Double,
    ): Outcome {
        val elapsedMinutes = (now - correctionLoggedAt) / 60_000L
        if (elapsedMinutes < windowMinutes) return Outcome.WINDOW_OPEN

        if (currentValue == null) return Outcome.INCONCLUSIVE
        if (currentValue > lowThreshold) return Outcome.RESPONDING_OR_RESOLVED
        if (currentRatePerMinute != null && currentRatePerMinute >= responseRateThreshold) return Outcome.RESPONDING_OR_RESOLVED

        return Outcome.NOT_RESPONDING
    }
}
