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
}
