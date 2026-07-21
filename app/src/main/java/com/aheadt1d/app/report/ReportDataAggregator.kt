package com.aheadt1d.app.report

import android.content.Context
import com.aheadt1d.app.chart.ChartDataSource
import com.aheadt1d.app.chart.ChartRange
import com.aheadt1d.app.events.UserEvent
import com.aheadt1d.app.health.GlucosePoint
import java.time.Instant

/**
 * Everything a doctor report needs for one date range, bundled once so the
 * annotated and clinical PDFs are guaranteed to be built from the exact same
 * underlying data - they only ever differ in which parts of this they draw.
 */
data class ReportData(
    val startDate: Instant,
    val endDate: Instant,
    val readings: List<GlucosePoint>,
    val events: List<UserEvent>,
    val metrics: AgpMetrics
)

/**
 * Queries Health Connect + the existing UserEvent table for [startDate,
 * endDate] and bundles the result, via the shared ChartDataSource (also used
 * by the live in-app chart) so the report can never diverge from the live
 * chart in what a given date range actually contains.
 */
object ReportDataAggregator {
    suspend fun aggregate(context: Context, startDate: Instant, endDate: Instant): ReportData {
        val data = ChartDataSource.load(context, ChartRange(startDate, endDate))
        val metrics = AgpMetricsCalculator.calculate(data.readings)

        return ReportData(
            startDate = startDate,
            endDate = endDate,
            readings = data.readings,
            events = data.events,
            metrics = metrics
        )
    }
}
