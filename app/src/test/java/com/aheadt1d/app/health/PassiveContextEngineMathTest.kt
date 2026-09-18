package com.aheadt1d.app.health

import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveContextEngineMathTest {

    private fun instantMinutesAgo(minutes: Long): Instant =
        Instant.now().minusSeconds(minutes * 60)

    @Test
    fun `computeDwellMinutes returns null when glucose is in range`() {
        val history = listOf(
            GlucosePoint(instantMinutesAgo(15), 120),
            GlucosePoint(instantMinutesAgo(10), 125),
            GlucosePoint(instantMinutesAgo(5), 130),
            GlucosePoint(instantMinutesAgo(0), 135)
        )
        val dwell = PassiveContextEngine.computeDwellMinutes(135, history)
        assertNull("In-range glucose should have no dwell time", dwell)
    }

    @Test
    fun `computeDwellMinutes handles contiguous high readings`() {
        val history = listOf(
            GlucosePoint(instantMinutesAgo(60), 190),
            GlucosePoint(instantMinutesAgo(45), 200),
            GlucosePoint(instantMinutesAgo(30), 210),
            GlucosePoint(instantMinutesAgo(15), 205),
            GlucosePoint(instantMinutesAgo(0), 215)
        )
        val dwell = PassiveContextEngine.computeDwellMinutes(215, history)
        assertEquals(60L, dwell)
    }

    @Test
    fun `computeDwellMinutes breaks dwell on gap exceeding maxGapMinutes`() {
        // Readings:
        // t-75m: 210
        // t-55m: 215  <-- 35m gap here to next reading (exceeds 20m threshold)
        // t-20m: 220
        // t-10m: 225
        // t-0m: 230
        val history = listOf(
            GlucosePoint(instantMinutesAgo(75), 210),
            GlucosePoint(instantMinutesAgo(55), 215),
            GlucosePoint(instantMinutesAgo(20), 220),
            GlucosePoint(instantMinutesAgo(10), 225),
            GlucosePoint(instantMinutesAgo(0), 230)
        )
        val dwell = PassiveContextEngine.computeDwellMinutes(230, history, maxGapMinutes = 20)
        assertEquals("Dwell should stop at the 35m gap, measuring from t-20m to t-0m", 20L, dwell)
    }

    @Test
    fun `computeDwellMinutes dedups duplicate readings within 90 seconds`() {
        val base = Instant.ofEpochMilli(1_700_000_000_000L)
        val history = listOf(
            GlucosePoint(base.minusSeconds(1800), 200),
            GlucosePoint(base.minusSeconds(1500), 205),
            GlucosePoint(base.minusSeconds(1200), 208),
            GlucosePoint(base.minusSeconds(900), 210),
            // Two duplicate points recorded 30s apart at ~10m ago
            GlucosePoint(base.minusSeconds(600), 212),
            GlucosePoint(base.minusSeconds(570), 212),
            GlucosePoint(base.minusSeconds(300), 215),
            GlucosePoint(base, 220)
        )
        val dwell = PassiveContextEngine.computeDwellMinutes(220, history)
        assertEquals(30L, dwell)
    }

    @Test
    fun `classifyCurvature dedups points and prevents zero delta-t acceleration spikes`() {
        val now = Instant.now()
        // Duplicate readings within 30s that would have produced delta-t ~ 0 without dedup
        val history = listOf(
            GlucosePoint(now.minusSeconds(600), 180),
            GlucosePoint(now.minusSeconds(300), 195),
            GlucosePoint(now.minusSeconds(290), 196), // near duplicate
            GlucosePoint(now, 200)
        )
        // Rate is 0.5 mg/dL/min, gentle curvature -> should classify as FLAT_PLATEAU without crashing or skew
        val state = PassiveContextEngine.classifyCurvature(history, currentRate = 0.5)
        assertEquals(PassiveContextEngine.CurvatureState.FLAT_PLATEAU, state)
    }

    @Test
    fun `classifyCircadian correctly identifies circadian windows`() {
        // Nocturnal sleep: 03:00 and 23:30
        assertEquals(PassiveContextEngine.CircadianPhase.NOCTURNAL_SLEEP, PassiveContextEngine.classifyCircadian(LocalTime.of(3, 0)))
        assertEquals(PassiveContextEngine.CircadianPhase.NOCTURNAL_SLEEP, PassiveContextEngine.classifyCircadian(LocalTime.of(23, 30)))

        // Dawn surge: 06:30 and 07:15
        assertEquals(PassiveContextEngine.CircadianPhase.DAWN_SURGE, PassiveContextEngine.classifyCircadian(LocalTime.of(6, 30)))

        // Evening wind down: 21:00
        assertEquals(PassiveContextEngine.CircadianPhase.EVENING_WIND_DOWN, PassiveContextEngine.classifyCircadian(LocalTime.of(21, 0)))
    }
}
