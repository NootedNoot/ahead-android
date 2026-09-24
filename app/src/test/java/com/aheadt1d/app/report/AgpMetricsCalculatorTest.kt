package com.aheadt1d.app.report

import com.aheadt1d.app.health.GlucosePoint
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgpMetricsCalculatorTest {

    private val start = Instant.parse("2026-01-01T00:00:00Z")

    private fun points(sgvs: List<Int>, intervalMinutes: Long = 5): List<GlucosePoint> =
        sgvs.mapIndexed { i, sgv -> GlucosePoint(time = start.plus(i * intervalMinutes, ChronoUnit.MINUTES), sgv = sgv) }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 0.001) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) <= tolerance)
    }

    @Test
    fun `empty list yields all-zero metrics, not a crash`() {
        val m = AgpMetricsCalculator.calculate(emptyList())
        assertEquals(0, m.readingsCount)
        assertClose(0.0, m.meanGlucose)
        assertClose(0.0, m.gmi)
        assertClose(0.0, m.coefficientOfVariation)
        assertClose(0.0, m.timeInRangePercent)
        assertClose(0.0, m.daysOfData)
    }

    @Test
    fun `flat 100 mgdl series has zero variability and known GMI`() {
        val m = AgpMetricsCalculator.calculate(points(List(20) { 100 }))
        assertClose(100.0, m.meanGlucose)
        assertClose(0.0, m.coefficientOfVariation)
        assertClose(3.31 + 0.02392 * 100.0, m.gmi)
        assertClose(100.0, m.timeInRangePercent)
        assertClose(0.0, m.timeBelowRangeL1Percent)
        assertClose(0.0, m.timeAboveRangeL1Percent)
    }

    @Test
    fun `range buckets partition evenly across all five zones`() {
        // One reading in each bucket: <54, 54-69, 70-180, 181-250, >250
        val m = AgpMetricsCalculator.calculate(points(listOf(50, 60, 150, 200, 300)))
        assertEquals(5, m.readingsCount)
        assertClose(20.0, m.timeBelowRangeL2Percent) // 50
        assertClose(20.0, m.timeBelowRangeL1Percent) // 60
        assertClose(20.0, m.timeInRangePercent)       // 150
        assertClose(20.0, m.timeAboveRangeL1Percent)  // 200
        assertClose(20.0, m.timeAboveRangeL2Percent)  // 300
    }

    @Test
    fun `bucket boundaries are inclusive where the spec says so`() {
        val m = AgpMetricsCalculator.calculate(points(listOf(53, 54, 69, 70, 180, 181, 250, 251)))
        // 53 -> L2 low, 54 & 69 -> L1 low, 70 & 180 -> in range,
        // 181 & 250 -> L1 high, 251 -> L2 high
        assertClose(1.0 / 8 * 100, m.timeBelowRangeL2Percent)
        assertClose(2.0 / 8 * 100, m.timeBelowRangeL1Percent)
        assertClose(2.0 / 8 * 100, m.timeInRangePercent)
        assertClose(2.0 / 8 * 100, m.timeAboveRangeL1Percent)
        assertClose(1.0 / 8 * 100, m.timeAboveRangeL2Percent)
    }

    @Test
    fun `coefficient of variation matches a hand-computed population stddev`() {
        // 90, 100, 110 -> mean 100, population variance = ((10^2)+0+(10^2))/3 = 66.67, stddev ~8.165
        val m = AgpMetricsCalculator.calculate(points(listOf(90, 100, 110)))
        assertClose(100.0, m.meanGlucose)
        assertClose(8.164965809, m.coefficientOfVariation, tolerance = 0.0001) // (8.1650/100)*100
    }

    @Test
    fun `daysOfData spans first to last reading, not reading count`() {
        val sevenDayReadings = points(sgvs = List(3) { 100 }, intervalMinutes = 7 * 24 * 60 / 2)
        val m = AgpMetricsCalculator.calculate(sevenDayReadings)
        assertClose(7.0, m.daysOfData, tolerance = 0.01)
    }

    @Test
    fun `coverage flags fire below their respective thresholds and not above`() {
        val short = AgpMetricsCalculator.calculate(points(List(2) { 100 }, intervalMinutes = 3 * 24 * 60))
        assertTrue(short.isBelowMinimumUsableCoverage)
        assertTrue(short.isBelowRecommendedCoverage)

        val tenDays = AgpMetricsCalculator.calculate(points(List(2) { 100 }, intervalMinutes = 10 * 24 * 60))
        assertFalse(tenDays.isBelowMinimumUsableCoverage)
        assertTrue(tenDays.isBelowRecommendedCoverage)

        val twentyDays = AgpMetricsCalculator.calculate(points(List(2) { 100 }, intervalMinutes = 20 * 24 * 60))
        assertFalse(twentyDays.isBelowMinimumUsableCoverage)
        assertFalse(twentyDays.isBelowRecommendedCoverage)
    }

    @Test
    fun `unsorted input is sorted before computing daysOfData and buckets`() {
        val outOfOrder = listOf(
            GlucosePoint(start.plus(10, ChronoUnit.MINUTES), 300),
            GlucosePoint(start, 50),
            GlucosePoint(start.plus(5, ChronoUnit.MINUTES), 150),
        )
        val m = AgpMetricsCalculator.calculate(outOfOrder)
        assertEquals(3, m.readingsCount)
        assertClose(1.0 / 3 * 100, m.timeBelowRangeL2Percent)
        assertClose(1.0 / 3 * 100, m.timeInRangePercent)
        assertClose(1.0 / 3 * 100, m.timeAboveRangeL2Percent)
    }

    @Test
    fun `14-day standard dataset with 4033 continuous readings aligns with ADA consensus metrics`() {
        val count = 14 * 288 + 1 // 14 full days of 5-min intervals
        val readings = mutableListOf<GlucosePoint>()
        for (i in 0 until count) {
            val t = start.plus(i * 5L, ChronoUnit.MINUTES)
            val step = i % 288
            val hour = step / 12.0
            val diurnal = 120 + 25 * kotlin.math.sin((hour - 8) * Math.PI / 12) +
                    15 * kotlin.math.sin((hour - 13) * Math.PI / 6)
            readings.add(GlucosePoint(t, diurnal.toInt()))
        }
        val m = AgpMetricsCalculator.calculate(readings)
        assertEquals(count, m.readingsCount)
        assertFalse(m.isBelowRecommendedCoverage)
        assertFalse(m.isBelowMinimumUsableCoverage)
        assertTrue("Days of data should be 14 days", m.daysOfData >= 14.0)
        assertTrue("Mean glucose should be within 110-150 mg/dL", m.meanGlucose in 110.0..150.0)
        assertTrue("CV should be well within <= 36% stability target", m.coefficientOfVariation <= 36.0)
        assertTrue("Time in range (70-180) should meet ADA > 70% target", m.timeInRangePercent >= 70.0)
        assertTrue("Time below range should be low (< 4%)", m.timeBelowRangeL1Percent + m.timeBelowRangeL2Percent < 4.0)
        assertTrue("GMI should be plausible", m.gmi in 5.8..6.8)
    }

    @Test
    fun `90-day large scale dataset with 25920 readings executes rapidly without overflow`() {
        val count = 90 * 288
        val readings = (0 until count).map { i ->
            val sgv = 110 + (i % 60)
            GlucosePoint(start.plus(i * 5L, ChronoUnit.MINUTES), sgv)
        }
        val t0 = System.currentTimeMillis()
        val m = AgpMetricsCalculator.calculate(readings)
        val elapsed = System.currentTimeMillis() - t0

        assertEquals(count, m.readingsCount)
        assertTrue("Days of data should reflect 90 days", m.daysOfData >= 89.9)
        assertTrue("Calculation of 25,920 points should take under 500ms", elapsed < 500)
        assertEquals(100.0, m.timeInRangePercent, 0.001)
    }

    @Test
    fun `sensor compression drops and sudden rebounds are calculated accurately without NaN`() {
        val readings = mutableListOf<GlucosePoint>()
        for (i in 0 until 10) readings.add(GlucosePoint(start.plus(i * 5L, ChronoUnit.MINUTES), 120))
        for (i in 10 until 13) readings.add(GlucosePoint(start.plus(i * 5L, ChronoUnit.MINUTES), 42))
        for (i in 13 until 20) readings.add(GlucosePoint(start.plus(i * 5L, ChronoUnit.MINUTES), 120))

        val m = AgpMetricsCalculator.calculate(readings)
        assertEquals(20, m.readingsCount)
        assertFalse(m.meanGlucose.isNaN())
        assertFalse(m.coefficientOfVariation.isNaN())
        assertFalse(m.gmi.isNaN())
        assertEquals(3.0 / 20.0 * 100.0, m.timeBelowRangeL2Percent, 0.001)
        assertEquals(17.0 / 20.0 * 100.0, m.timeInRangePercent, 0.001)
    }

    @Test
    fun `multi-day sensor warmup outage gaps do not corrupt daysOfData or bucket percentages`() {
        val readings = mutableListOf<GlucosePoint>()
        for (i in 0 until 288 * 3) {
            readings.add(GlucosePoint(start.plus(i * 5L, ChronoUnit.MINUTES), 110))
        }
        val resumeStart = start.plus(7 * 24 * 60L, ChronoUnit.MINUTES)
        for (i in 0 until 288 * 3) {
            readings.add(GlucosePoint(resumeStart.plus(i * 5L, ChronoUnit.MINUTES), 130))
        }

        val m = AgpMetricsCalculator.calculate(readings)
        assertEquals(288 * 6, m.readingsCount)
        assertTrue("Days of data spans entire period ~10 days", m.daysOfData >= 9.9)
        assertEquals(100.0, m.timeInRangePercent, 0.001)
        assertEquals(0.0, m.timeBelowRangeL1Percent, 0.001)
        assertEquals(0.0, m.timeAboveRangeL1Percent, 0.001)
    }
}
