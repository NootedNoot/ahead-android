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
            SpokenAlertText.red(99, -2.2, 70, null, recovering = false),
        )
    }

    @Test
    fun `red alert always says the glucose number`() {
        val said = SpokenAlertText.red(82, -2.8, 40, null, recovering = false)
        assertEquals(
            "Urgent. Glucose is at 82 and falling fast at minus 2.8 points a minute. Projected 40 in fifteen minutes. Check now.",
            said,
        )
    }

    @Test
    fun `recovering red keeps its calmer wording, still says the number, and adds the rate`() {
        assertEquals(
            "Still low at 68, but rising at plus 0.9 points a minute. Projected 75 in fifteen minutes. Keep monitoring.",
            SpokenAlertText.red(68, 0.9, 75, null, recovering = true),
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
            SpokenAlertText.red(82, null, 60, null, recovering = false),
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
