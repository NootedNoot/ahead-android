package com.aheadt1d.app.alerts

import com.aheadt1d.app.health.GlucosePoint
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateauMathTest {

    private val start = Instant.parse("2026-01-01T00:00:00Z")

    private fun points(sgvs: List<Int>, intervalMinutes: Long = 5): List<GlucosePoint> =
        sgvs.mapIndexed { i, sgv -> GlucosePoint(time = start.plus(i * intervalMinutes, ChronoUnit.MINUTES), sgv = sgv) }

    @Test
    fun `empty list yields null, not a crash`() {
        assertNull(PlateauMath.currentPlateauDurationMinutes(emptyList(), threshold = 250))
    }

    @Test
    fun `latest reading below threshold yields null`() {
        val pts = points(listOf(260, 255, 240))
        assertNull(PlateauMath.currentPlateauDurationMinutes(pts, threshold = 250))
    }

    @Test
    fun `single qualifying reading yields zero minutes`() {
        val pts = points(listOf(200, 260))
        assertEquals(0L, PlateauMath.currentPlateauDurationMinutes(pts, threshold = 250))
    }

    @Test
    fun `continuous run above threshold sums full duration`() {
        // 19 points, 5 min apart, all >= 250 -> 90 minutes end to end.
        val pts = points(List(19) { 260 })
        assertEquals(90L, PlateauMath.currentPlateauDurationMinutes(pts, threshold = 250))
    }

    @Test
    fun `a dip below threshold breaks the streak, duration counts only since the dip`() {
        // 260,260,260 (0,5,10) dip 200 (15) then 260,260,260,260 (20,25,30,35)
        val pts = points(listOf(260, 260, 260, 200, 260, 260, 260, 260))
        // Streak restarts at minute 20 (index 4); latest is minute 35 -> 15 min.
        assertEquals(15L, PlateauMath.currentPlateauDurationMinutes(pts, threshold = 250))
    }

    @Test
    fun `a gap wider than maxGapMinutes breaks contiguity even though both sides are high`() {
        val early = points(listOf(260, 260), intervalMinutes = 5) // t=0, t=5
        val late = listOf(
            GlucosePoint(time = start.plus(60, ChronoUnit.MINUTES), sgv = 260),
            GlucosePoint(time = start.plus(65, ChronoUnit.MINUTES), sgv = 260),
        )
        val pts = early + late
        // Gap between t=5 and t=60 is 55 min > default 20 min maxGap.
        assertEquals(5L, PlateauMath.currentPlateauDurationMinutes(pts, threshold = 250))
    }

    @Test
    fun `exactly at threshold counts as qualifying`() {
        val pts = points(listOf(250, 250, 250))
        assertEquals(10L, PlateauMath.currentPlateauDurationMinutes(pts, threshold = 250))
    }

    @Test
    fun `unsorted input is handled correctly`() {
        val ordered = points(listOf(260, 260, 260))
        val shuffled = listOf(ordered[2], ordered[0], ordered[1])
        assertEquals(10L, PlateauMath.currentPlateauDurationMinutes(shuffled, threshold = 250))
    }

    @Test
    fun `tierFor below highDuration is zero`() {
        assertEquals(0, PlateauMath.tierFor(durationMinutes = 89, highDurationMinutes = 90, escalationStepMinutes = 60))
    }

    @Test
    fun `tierFor at exactly highDuration is tier one`() {
        assertEquals(1, PlateauMath.tierFor(durationMinutes = 90, highDurationMinutes = 90, escalationStepMinutes = 60))
    }

    @Test
    fun `tierFor escalates one tier per full escalation step past highDuration`() {
        assertEquals(1, PlateauMath.tierFor(durationMinutes = 149, highDurationMinutes = 90, escalationStepMinutes = 60))
        assertEquals(2, PlateauMath.tierFor(durationMinutes = 150, highDurationMinutes = 90, escalationStepMinutes = 60))
        assertEquals(3, PlateauMath.tierFor(durationMinutes = 210, highDurationMinutes = 90, escalationStepMinutes = 60))
    }

    @Test
    fun `tierFor never divides by zero on a misconfigured zero escalation step`() {
        assertEquals(91, PlateauMath.tierFor(durationMinutes = 180, highDurationMinutes = 90, escalationStepMinutes = 0))
    }

    @Test
    fun `hasDroppedBelowHysteresisFloor is false right at the floor`() {
        val pts = points(listOf(230))
        assertFalse(PlateauMath.hasDroppedBelowHysteresisFloor(pts, threshold = 250, hysteresisBuffer = 20))
    }

    @Test
    fun `hasDroppedBelowHysteresisFloor is true just under the floor`() {
        val pts = points(listOf(229))
        assertTrue(PlateauMath.hasDroppedBelowHysteresisFloor(pts, threshold = 250, hysteresisBuffer = 20))
    }

    @Test
    fun `hasDroppedBelowHysteresisFloor uses only the latest reading`() {
        val pts = points(listOf(100, 260))
        assertFalse(PlateauMath.hasDroppedBelowHysteresisFloor(pts, threshold = 250, hysteresisBuffer = 20))
    }

    @Test
    fun `hasDroppedBelowHysteresisFloor on empty list is false`() {
        assertFalse(PlateauMath.hasDroppedBelowHysteresisFloor(emptyList(), threshold = 250, hysteresisBuffer = 20))
    }
}
