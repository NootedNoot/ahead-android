package com.aheadt1d.app.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

class CorrectionResponseMathTest {

    private val loggedAt = 1_000_000_000_000L // arbitrary epoch ms anchor
    private val windowMinutes = 45L
    private val highThreshold = 250
    private val responseRateThreshold = -1.0

    private fun minutesLater(minutes: Long) = loggedAt + minutes * 60_000L

    @Test
    fun `still inside the window is WINDOW_OPEN regardless of value or rate`() {
        val outcome = CorrectionResponseMath.evaluate(
            correctionLoggedAt = loggedAt, now = minutesLater(44), windowMinutes = windowMinutes,
            currentValue = 400, currentRatePerMinute = 5.0,
            highThreshold = highThreshold, responseRateThreshold = responseRateThreshold,
        )
        assertEquals(CorrectionResponseMath.Outcome.WINDOW_OPEN, outcome)
    }

    @Test
    fun `exactly at the window boundary is evaluated, not open`() {
        val outcome = CorrectionResponseMath.evaluate(
            correctionLoggedAt = loggedAt, now = minutesLater(45), windowMinutes = windowMinutes,
            currentValue = 300, currentRatePerMinute = 0.0,
            highThreshold = highThreshold, responseRateThreshold = responseRateThreshold,
        )
        assertEquals(CorrectionResponseMath.Outcome.NOT_RESPONDING, outcome)
    }

    @Test
    fun `value dropped below threshold counts as responding`() {
        val outcome = CorrectionResponseMath.evaluate(
            correctionLoggedAt = loggedAt, now = minutesLater(50), windowMinutes = windowMinutes,
            currentValue = 249, currentRatePerMinute = 0.0,
            highThreshold = highThreshold, responseRateThreshold = responseRateThreshold,
        )
        assertEquals(CorrectionResponseMath.Outcome.RESPONDING_OR_RESOLVED, outcome)
    }

    @Test
    fun `still elevated but trending down meaningfully counts as responding`() {
        val outcome = CorrectionResponseMath.evaluate(
            correctionLoggedAt = loggedAt, now = minutesLater(50), windowMinutes = windowMinutes,
            currentValue = 300, currentRatePerMinute = -1.5,
            highThreshold = highThreshold, responseRateThreshold = responseRateThreshold,
        )
        assertEquals(CorrectionResponseMath.Outcome.RESPONDING_OR_RESOLVED, outcome)
    }

    @Test
    fun `still elevated and flat is NOT_RESPONDING`() {
        val outcome = CorrectionResponseMath.evaluate(
            correctionLoggedAt = loggedAt, now = minutesLater(50), windowMinutes = windowMinutes,
            currentValue = 300, currentRatePerMinute = 0.0,
            highThreshold = highThreshold, responseRateThreshold = responseRateThreshold,
        )
        assertEquals(CorrectionResponseMath.Outcome.NOT_RESPONDING, outcome)
    }

    @Test
    fun `still elevated and rising is NOT_RESPONDING`() {
        val outcome = CorrectionResponseMath.evaluate(
            correctionLoggedAt = loggedAt, now = minutesLater(50), windowMinutes = windowMinutes,
            currentValue = 300, currentRatePerMinute = 2.0,
            highThreshold = highThreshold, responseRateThreshold = responseRateThreshold,
        )
        assertEquals(CorrectionResponseMath.Outcome.NOT_RESPONDING, outcome)
    }

    @Test
    fun `rate barely not meeting the meaningful-negative bar is still NOT_RESPONDING`() {
        val outcome = CorrectionResponseMath.evaluate(
            correctionLoggedAt = loggedAt, now = minutesLater(50), windowMinutes = windowMinutes,
            currentValue = 300, currentRatePerMinute = -0.5,
            highThreshold = highThreshold, responseRateThreshold = responseRateThreshold,
        )
        assertEquals(CorrectionResponseMath.Outcome.NOT_RESPONDING, outcome)
    }

    @Test
    fun `rate exactly at the threshold counts as responding`() {
        val outcome = CorrectionResponseMath.evaluate(
            correctionLoggedAt = loggedAt, now = minutesLater(50), windowMinutes = windowMinutes,
            currentValue = 300, currentRatePerMinute = -1.0,
            highThreshold = highThreshold, responseRateThreshold = responseRateThreshold,
        )
        assertEquals(CorrectionResponseMath.Outcome.RESPONDING_OR_RESOLVED, outcome)
    }

    @Test
    fun `missing current value fails safe as responding, never fires on absent data`() {
        val outcome = CorrectionResponseMath.evaluate(
            correctionLoggedAt = loggedAt, now = minutesLater(50), windowMinutes = windowMinutes,
            currentValue = null, currentRatePerMinute = null,
            highThreshold = highThreshold, responseRateThreshold = responseRateThreshold,
        )
        assertEquals(CorrectionResponseMath.Outcome.RESPONDING_OR_RESOLVED, outcome)
    }

    @Test
    fun `missing rate with still-elevated value is NOT_RESPONDING, not guessed as improving`() {
        val outcome = CorrectionResponseMath.evaluate(
            correctionLoggedAt = loggedAt, now = minutesLater(50), windowMinutes = windowMinutes,
            currentValue = 300, currentRatePerMinute = null,
            highThreshold = highThreshold, responseRateThreshold = responseRateThreshold,
        )
        assertEquals(CorrectionResponseMath.Outcome.NOT_RESPONDING, outcome)
    }
}
