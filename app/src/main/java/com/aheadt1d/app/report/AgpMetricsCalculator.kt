package com.aheadt1d.app.report

import com.aheadt1d.app.health.GlucosePoint
import java.time.Duration
import kotlin.math.sqrt

/**
 * Standard AGP-style summary metrics for a date range - the same numbers
 * Dexcom Clarity / LibreView / Nightscout reports show, so they read as
 * familiar to any endo or PCP. See AgpMetricsCalculator for the formulas.
 */
data class AgpMetrics(
    val meanGlucose: Double,
    val gmi: Double,                    // estimated A1C from CGM data
    val coefficientOfVariation: Double,  // glucose variability %
    val timeInRangePercent: Double,      // 70-180 mg/dL
    val timeBelowRangeL1Percent: Double, // 54-69
    val timeBelowRangeL2Percent: Double, // <54
    val timeAboveRangeL1Percent: Double, // 181-250
    val timeAboveRangeL2Percent: Double, // >250
    val readingsCount: Int,
    val daysOfData: Double
)

/** daysOfData below this is flagged to the user as "not enough data to be
 *  fully reliable" - never hidden, per the AGP standard's own guidance. */
val AgpMetrics.isBelowRecommendedCoverage: Boolean
    get() = daysOfData < AgpMetricsCalculator.RECOMMENDED_MINIMUM_DAYS

/** Below this, the report is flagged as having too little data to be
 *  meaningful at all (as opposed to merely short of the 14-day ideal). */
val AgpMetrics.isBelowMinimumUsableCoverage: Boolean
    get() = daysOfData < AgpMetricsCalculator.MINIMUM_USABLE_DAYS

/**
 * Pure calculation, no Android dependencies (GlucosePoint itself is just
 * java.time.Instant + Int, so this is trivially unit-testable on the JVM
 * with no Robolectric/instrumentation needed).
 */
object AgpMetricsCalculator {
    // AGP standard wants 14 days of data for a reliable/representative
    // snapshot. 7 is an absolute floor below which the numbers risk being
    // more noise than signal - both are surfaced to the user, never hidden,
    // via the isBelowRecommendedCoverage/isBelowMinimumUsableCoverage flags.
    const val RECOMMENDED_MINIMUM_DAYS = 14.0
    const val MINIMUM_USABLE_DAYS = 7.0

    fun calculate(readings: List<GlucosePoint>): AgpMetrics {
        if (readings.isEmpty()) {
            return AgpMetrics(
                meanGlucose = 0.0,
                gmi = 0.0,
                coefficientOfVariation = 0.0,
                timeInRangePercent = 0.0,
                timeBelowRangeL1Percent = 0.0,
                timeBelowRangeL2Percent = 0.0,
                timeAboveRangeL1Percent = 0.0,
                timeAboveRangeL2Percent = 0.0,
                readingsCount = 0,
                daysOfData = 0.0
            )
        }

        val sorted = readings.sortedBy { it.time }
        val values = sorted.map { it.sgv.toDouble() }
        val mean = values.average()

        // Population standard deviation (divides by n, not n-1) - the full
        // set of readings in the chosen range is treated as the complete
        // population being summarized, not a sample drawn from a larger one.
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val stdDev = sqrt(variance)
        val cv = if (mean == 0.0) 0.0 else (stdDev / mean) * 100.0

        val gmi = 3.31 + (0.02392 * mean)

        fun percentOf(predicate: (Int) -> Boolean): Double =
            sorted.count { predicate(it.sgv) } * 100.0 / sorted.size

        val daysOfData = Duration.between(sorted.first().time, sorted.last().time).toMinutes() / (24.0 * 60.0)

        return AgpMetrics(
            meanGlucose = mean,
            gmi = gmi,
            coefficientOfVariation = cv,
            timeInRangePercent = percentOf { it in 70..180 },
            timeBelowRangeL1Percent = percentOf { it in 54..69 },
            timeBelowRangeL2Percent = percentOf { it < 54 },
            timeAboveRangeL1Percent = percentOf { it in 181..250 },
            timeAboveRangeL2Percent = percentOf { it > 250 },
            readingsCount = sorted.size,
            daysOfData = daysOfData
        )
    }
}
