package com.aheadt1d.app.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomThresholdMathTest {

    private fun valueThreshold(
        direction: CustomThreshold.Direction,
        amount: Double,
        currentlyCrossed: Boolean = false,
        lastFiredAtMetric: Double? = null,
    ) = CustomThreshold(
        id = "t1", kind = CustomThreshold.Kind.VALUE, direction = direction, amount = amount,
        label = "", temporary = false, currentlyCrossed = currentlyCrossed, lastFiredAtMetric = lastFiredAtMetric,
    )

    private fun rateThreshold(
        direction: CustomThreshold.Direction,
        amount: Double,
        currentlyCrossed: Boolean = false,
        lastFiredAtMetric: Double? = null,
    ) = CustomThreshold(
        id = "t2", kind = CustomThreshold.Kind.RATE, direction = direction, amount = amount,
        label = "", temporary = false, currentlyCrossed = currentlyCrossed, lastFiredAtMetric = lastFiredAtMetric,
    )

    // --- Fresh crossings ----------------------------------------------------

    @Test
    fun `a value below a RISING threshold is not crossed`() {
        val result = CustomThresholdMath.evaluate(valueThreshold(CustomThreshold.Direction.RISING, 300.0), value = 250, rate = null)
        assertEquals(CustomThresholdMath.Outcome.NONE, result.outcome)
        assertEquals(false, result.isCrossedNow)
    }

    @Test
    fun `a value at or above a RISING threshold freshly crosses`() {
        val result = CustomThresholdMath.evaluate(valueThreshold(CustomThreshold.Direction.RISING, 300.0), value = 300, rate = null)
        assertEquals(CustomThresholdMath.Outcome.FRESH_CROSS, result.outcome)
        assertEquals(true, result.isCrossedNow)
    }

    @Test
    fun `a value at or below a FALLING threshold freshly crosses`() {
        val result = CustomThresholdMath.evaluate(valueThreshold(CustomThreshold.Direction.FALLING, 90.0), value = 85, rate = null)
        assertEquals(CustomThresholdMath.Outcome.FRESH_CROSS, result.outcome)
    }

    @Test
    fun `a rate at or beyond a RISING rate threshold freshly crosses`() {
        val result = CustomThresholdMath.evaluate(rateThreshold(CustomThreshold.Direction.RISING, 5.0), value = 150, rate = 5.5)
        assertEquals(CustomThresholdMath.Outcome.FRESH_CROSS, result.outcome)
    }

    @Test
    fun `a rate at or beyond a FALLING rate threshold freshly crosses`() {
        val result = CustomThresholdMath.evaluate(rateThreshold(CustomThreshold.Direction.FALLING, -5.0), value = 150, rate = -6.0)
        assertEquals(CustomThresholdMath.Outcome.FRESH_CROSS, result.outcome)
    }

    @Test
    fun `a null rate is never crossed, regardless of direction`() {
        val rising = CustomThresholdMath.evaluate(rateThreshold(CustomThreshold.Direction.RISING, 5.0), value = 150, rate = null)
        val falling = CustomThresholdMath.evaluate(rateThreshold(CustomThreshold.Direction.FALLING, -5.0), value = 150, rate = null)
        assertEquals(CustomThresholdMath.Outcome.NONE, rising.outcome)
        assertEquals(CustomThresholdMath.Outcome.NONE, falling.outcome)
        assertNull(rising.metric)
    }

    // --- No repeat ping while merely "still crossed" -------------------------

    @Test
    fun `still crossed at the same value as the last fire is not newsworthy again`() {
        val threshold = valueThreshold(CustomThreshold.Direction.RISING, 300.0, currentlyCrossed = true, lastFiredAtMetric = 305.0)
        val result = CustomThresholdMath.evaluate(threshold, value = 310, rate = null) // +5, under the 20 delta
        assertEquals(CustomThresholdMath.Outcome.NONE, result.outcome)
        assertEquals(true, result.isCrossedNow) // still crossed, just not re-pinged
    }

    @Test
    fun `escalating value threshold by the full delta fires again`() {
        val threshold = valueThreshold(CustomThreshold.Direction.RISING, 300.0, currentlyCrossed = true, lastFiredAtMetric = 305.0)
        val result = CustomThresholdMath.evaluate(threshold, value = 325, rate = null) // +20, meets the delta exactly
        assertEquals(CustomThresholdMath.Outcome.ESCALATED, result.outcome)
    }

    @Test
    fun `escalating a FALLING value threshold further down fires again`() {
        val threshold = valueThreshold(CustomThreshold.Direction.FALLING, 90.0, currentlyCrossed = true, lastFiredAtMetric = 85.0)
        val result = CustomThresholdMath.evaluate(threshold, value = 60, rate = null) // -25, past the -20 delta
        assertEquals(CustomThresholdMath.Outcome.ESCALATED, result.outcome)
    }

    @Test
    fun `escalating a rate threshold by its own smaller delta fires again`() {
        val threshold = rateThreshold(CustomThreshold.Direction.RISING, 5.0, currentlyCrossed = true, lastFiredAtMetric = 5.2)
        val result = CustomThresholdMath.evaluate(threshold, value = 150, rate = 6.3) // +1.1, past the 1.0 delta
        assertEquals(CustomThresholdMath.Outcome.ESCALATED, result.outcome)
    }

    // --- Recovery resets state, doesn't itself notify -------------------------

    @Test
    fun `dropping clearly back under a RISING threshold, past the hysteresis buffer, recovers`() {
        // 300 - VALUE_ESCALATION_DELTA(20) = 280 is the clear boundary;
        // this needs to go strictly below it.
        val threshold = valueThreshold(CustomThreshold.Direction.RISING, 300.0, currentlyCrossed = true, lastFiredAtMetric = 305.0)
        val result = CustomThresholdMath.evaluate(threshold, value = 270, rate = null)
        assertEquals(CustomThresholdMath.Outcome.RECOVERED, result.outcome)
        assertEquals(false, result.isCrossedNow)
    }

    @Test
    fun `never having been crossed and still not crossed is just NONE, not RECOVERED`() {
        val result = CustomThresholdMath.evaluate(valueThreshold(CustomThreshold.Direction.RISING, 300.0), value = 150, rate = null)
        assertEquals(CustomThresholdMath.Outcome.NONE, result.outcome)
    }

    // --- Recovery hysteresis (2026-09-13) --------------------------------
    // Without a buffer, a metric wobbling right at the boundary (e.g. a rate
    // hovering +5.0/+4.9 around a +5.0 threshold) would flip RECOVERED then
    // FRESH_CROSS every other check cycle - two override-silence pings for
    // one continuous episode. Mirrors AlertCoordinator's own documented
    // "flap" concern for the ordinary yellow/red path.

    @Test
    fun `dipping just barely under a RISING threshold, still within the hysteresis buffer, is not yet a recovery`() {
        // 300 - 20(delta) = 280 exactly - not YET below the buffer.
        val threshold = valueThreshold(CustomThreshold.Direction.RISING, 300.0, currentlyCrossed = true, lastFiredAtMetric = 305.0)
        val result = CustomThresholdMath.evaluate(threshold, value = 285, rate = null)
        assertEquals(CustomThresholdMath.Outcome.NONE, result.outcome)
        assertEquals(true, result.isCrossedNow) // still treated as crossed, just not re-pinged
    }

    @Test
    fun `dipping just barely above a FALLING threshold, still within the hysteresis buffer, is not yet a recovery`() {
        val threshold = valueThreshold(CustomThreshold.Direction.FALLING, 90.0, currentlyCrossed = true, lastFiredAtMetric = 85.0)
        val result = CustomThresholdMath.evaluate(threshold, value = 95, rate = null) // 90 + 20 = 110 is the clear boundary
        assertEquals(CustomThresholdMath.Outcome.NONE, result.outcome)
        assertEquals(true, result.isCrossedNow)
    }

    @Test
    fun `missing data (null rate) still clears immediately, hysteresis does not apply to a genuinely missing signal`() {
        val threshold = rateThreshold(CustomThreshold.Direction.RISING, 5.0, currentlyCrossed = true, lastFiredAtMetric = 6.0)
        val result = CustomThresholdMath.evaluate(threshold, value = 150, rate = null)
        assertEquals(CustomThresholdMath.Outcome.RECOVERED, result.outcome)
        assertEquals(false, result.isCrossedNow)
    }

    // --- Unknown last-fired metric re-syncs by escalating, not silently comparing to itself ---
    // (2026-09-13) A currentlyCrossed=true threshold with no recorded
    // lastFiredAtMetric (reachable via an older/corrupted persisted entry)
    // used to compare the metric against itself, which can never register as
    // "moved further" - permanently unable to escalate until an unrelated
    // recovery reset it.

    @Test
    fun `still crossed with no recorded last-fired metric re-syncs by escalating once`() {
        val threshold = valueThreshold(CustomThreshold.Direction.RISING, 300.0, currentlyCrossed = true, lastFiredAtMetric = null)
        val result = CustomThresholdMath.evaluate(threshold, value = 305, rate = null)
        assertEquals(CustomThresholdMath.Outcome.ESCALATED, result.outcome)
    }

    // --- Disabled/expired thresholds are the coordinator's job, not this one -
    // (CustomThresholdMath has no concept of enabled/expiresAtMs at all - see
    // CustomThresholdCoordinator, which filters those out before calling
    // evaluate() in the first place.)
}
