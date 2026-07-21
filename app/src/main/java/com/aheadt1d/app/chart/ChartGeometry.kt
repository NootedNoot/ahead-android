package com.aheadt1d.app.chart

import java.time.Instant

/**
 * Shared time/value -> pixel mapping, extracted verbatim from
 * GlucoseReportChartRenderer's original local closures. Used identically by
 * the report Canvas renderer and (in future) the interactive export's
 * precomputed geometry - one formula, so a scaling bug only ever needs
 * fixing in one place.
 */
class ChartGeometry(
    range: ChartRange,
    private val plotLeft: Float,
    private val plotRight: Float,
    private val plotTop: Float,
    private val plotBottom: Float,
    private val yMin: Float,
    private val yMax: Float,
) {
    private val startMillis = range.start.toEpochMilli()
    private val endMillis = range.end.toEpochMilli().coerceAtLeast(startMillis + 1)

    fun xForTime(instant: Instant): Float {
        val fraction = (instant.toEpochMilli() - startMillis).toFloat() / (endMillis - startMillis).toFloat()
        return plotLeft + fraction.coerceIn(0f, 1f) * (plotRight - plotLeft)
    }

    fun yForValue(value: Float): Float {
        val fraction = ((value - yMin) / (yMax - yMin)).coerceIn(0f, 1f)
        return plotBottom - fraction * (plotBottom - plotTop)
    }
}
