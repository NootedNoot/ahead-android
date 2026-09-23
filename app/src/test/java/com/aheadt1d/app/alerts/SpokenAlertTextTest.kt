package com.aheadt1d.app.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

/** The words Ahead says out loud. Pure JVM - no Robolectric needed. */
class SpokenAlertTextTest {

    // --- the signed rate --------------------------------------------------------

    @Test
    fun `a falling rate is spoken with a minus and 'points a minute'`() {
        assertEquals(" at minus 2.8 points a minute", SpokenAlertText.rate(-2.8))
    }

    @Test
    fun `a rising rate is spoken with a plus, rounded to one decimal`() {
        assertEquals(" at plus 1.4 points a minute", SpokenAlertText.rate(1.44))
    }

    @Test
    fun `no rate, a non-finite rate, or a rate that rounds to zero says nothing`() {
        assertEquals("", SpokenAlertText.rate(null))
        assertEquals("", SpokenAlertText.rate(Double.NaN))
        assertEquals("", SpokenAlertText.rate(0.0))
        assertEquals("", SpokenAlertText.rate(0.03))
        assertEquals("", SpokenAlertText.rate(-0.04))
    }

    @Test
    fun `the smallest speakable rate is 0-1`() {
        assertEquals(" at minus 0.1 points a minute", SpokenAlertText.rate(-0.05))
    }

    // --- whole alerts: the glucose number, direction, signed rate, then the projection ----

    @Test
    fun `the owner's own example reads exactly the way he asked for it`() {
        // "glucose is at 99 and falling fast at minus 2.2 points a min projected 70 in 15 minutes"
        assertEquals(
            "Urgent. Glucose is at 99 and falling fast at minus 2.2 points a minute. Projected 70 in fifteen minutes. Check now.",
            SpokenAlertText.red(99, -2.2, 70, null, lowPhase = LowAlertPhase.URGENT),
        )
    }

    @Test
    fun `red alert always says the glucose number`() {
        val said = SpokenAlertText.red(82, -2.8, 40, null, lowPhase = LowAlertPhase.URGENT)
        assertEquals(
            "Urgent. Glucose is at 82 and falling fast at minus 2.8 points a minute. Projected 40 in fifteen minutes. Check now.",
            said,
        )
    }

    @Test
    fun `a still-low but rising reading gets the calmer RISING wording, not urgent copy`() {
        assertEquals(
            "Low at 68, but rising at plus 0.9 points a minute. Projected 75 in fifteen minutes. No need to re-treat yet.",
            SpokenAlertText.red(68, 0.9, 75, null, lowPhase = LowAlertPhase.RISING),
        )
    }

    @Test
    fun `a value that's crossed back above 70 but isn't confirmed stable never says 'still low'`() {
        // 2026-09-23 ticket: a real 79 mg/dL reading (already above the app's own 70 mg/dL
        // threshold) once said "Still low... rising" here - factually wrong per the owner's own
        // threshold. RECOVERING must say something else entirely.
        val said = SpokenAlertText.red(79, 0.8, 87, null, lowPhase = LowAlertPhase.RECOVERING)
        assertEquals(
            "Recovering. Glucose is at 79, back above seventy but not yet stable at plus 0.8 points a minute. Projected 87 in fifteen minutes.",
            said,
        )
        assertEquals(false, said.contains("Still low", ignoreCase = true))
    }

    @Test
    fun `a still-low flat or mildly-negative reading gets STANDARD wording, not full URGENT`() {
        assertEquals(
            "Glucose is at 68 and falling at minus 0.6 points a minute. Projected 62 in fifteen minutes. Keep monitoring.",
            SpokenAlertText.red(68, -0.6, 62, null, lowPhase = LowAlertPhase.STANDARD),
        )
    }

    @Test
    fun `yellow alert says the number, direction and rate`() {
        assertEquals(
            "Heads up. Glucose is at 120 and falling at minus 1.6 points a minute. Projected 96 in fifteen minutes.",
            SpokenAlertText.yellow(120, -1.6, 96, null),
        )
    }

    @Test
    fun `a rise is spoken the same way with a plus`() {
        assertEquals(
            "Heads up. Glucose is at 210 and rising fast at plus 2.6 points a minute. Projected 249 in fifteen minutes.",
            SpokenAlertText.yellow(210, 2.6, 249, null),
        )
    }

    @Test
    fun `when the rate is unknown the number is still said and the trend is called unknown`() {
        assertEquals(
            "Urgent. Glucose is at 82, trend unknown. Projected 60 in fifteen minutes. Check now.",
            SpokenAlertText.red(82, null, 60, null, lowPhase = LowAlertPhase.URGENT),
        )
    }

    @Test
    fun `a steady reading does not get a rate tacked on`() {
        assertEquals(
            "Heads up. Glucose is at 100 and holding steady. Projected 100 in fifteen minutes.",
            SpokenAlertText.yellow(100, 0.0, 100, null),
        )
    }

    @Test
    fun `the thirty-minute projection is used when it says more`() {
        assertEquals(
            "Heads up. Glucose is at 130 and falling at minus 1.2 points a minute. Projected 94 in thirty minutes.",
            SpokenAlertText.yellow(130, -1.2, 128, 94),
        )
    }
}
