package com.aheadt1d.app.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.aheadt1d.app.chart.AxisTicks
import com.aheadt1d.app.chart.ChartGeometry
import com.aheadt1d.app.chart.ChartRange
import com.aheadt1d.app.chart.EventMarker
import com.aheadt1d.app.chart.EventMarkerLayout
import com.aheadt1d.app.chart.GapSegmenter
import com.aheadt1d.app.chart.SeverityColoring
import com.aheadt1d.app.events.EventTag
import com.aheadt1d.app.events.UserEvent
import com.aheadt1d.app.health.GlucosePoint
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders a ReportData's glucose curve straight onto a Bitmap with plain
 * Canvas drawing primitives - deliberately NOT MPAndroidChart. This app has
 * no existing precedent for headless/offscreen MPAndroidChart rendering (it's
 * only ever attached to a live, laid-out View elsewhere in the codebase), and
 * a report that gets handed to a doctor needs to render correctly the first
 * time - not be the guinea pig for a rendering path that's never been
 * exercised before. Hand-rolled primitives also keep every pixel's source
 * (axis math, colors, gaps) fully explicit and easy to unit-test/reason about.
 *
 * The actual geometry/gap/marker/axis math lives in the shared
 * `com.aheadt1d.app.chart` package (also consumed by the live in-app chart
 * and the interactive export) - this file only does the Canvas-specific
 * drawing on top of it, so a scaling/gap/marker-positioning bug only ever
 * needs fixing once.
 *
 * v1 deliberately ships only the straightforward chronological line chart -
 * the "modal day" (all days overlaid on one 24h axis) AGP-standard view the
 * spec called out is left for a later pass, per the spec's own "v1 vs v2"
 * call: a plain chronological line is still valid and useful on its own.
 */
object GlucoseReportChartRenderer {
    // Fixed axis range (mirrors GraphActivity's "FULL" range mode) rather than
    // an auto-fit range - a doctor report should show the same axis every
    // time regardless of what this particular range's data happens to span,
    // so the shaded target band and severity coloring always mean the same
    // thing from report to report.
    private const val Y_MIN = 40f
    private const val Y_MAX = 400f
    private const val TARGET_LOW = 70f
    private const val TARGET_HIGH = 180f

    // Don't draw a connecting line across a data gap wider than this - a long
    // export can have real multi-hour/day holes (sensor off, nothing synced),
    // and a straight line across those would misleadingly imply readings that
    // don't exist.
    private val MAX_CONNECT_GAP: Duration = Duration.ofMinutes(20)

    private const val MARGIN_LEFT = 70f
    private const val MARGIN_RIGHT = 24f
    private const val MARGIN_TOP = 24f
    // Room below the plot for: the x-axis date labels close to the axis, and
    // (when annotated) up to MARKER_MAX_ROWS stacked rows of event numbers
    // further down, clear of both the axis line and each other.
    private const val MARGIN_BOTTOM = 132f

    private const val LINE_WIDTH = 3f

    // Points-per-bucket for the min/max band (see drawSegment) - fine enough
    // resolution that a real trend shape is still legible, coarse enough that
    // a dense multi-zone cluster reads as one coherent band instead of a
    // tangle of crossing, differently-colored segments.
    private const val BUCKET_WIDTH_PX = 3f

    // Minimum horizontal gap (px) between two event numbers before the later
    // one needs to move to another row - keeps closely-spaced events legible
    // instead of overlapping digits.
    private const val MARKER_MIN_SPACING_PX = 16f
    private const val MARKER_ROW_HEIGHT_PX = 15f
    // Cap on stagger rows. On a wide multi-week chart, an hour of real time
    // can compress to just a couple of pixels - several same-day events can
    // easily all fall within MARKER_MIN_SPACING_PX of each other, so this
    // needs more headroom than a typical 2-3 event cluster would suggest.
    // Beyond this many in one tight cluster, later markers fall back to
    // reusing the last row (rare in practice).
    private const val MARKER_MAX_ROWS = 5

    private val Y_AXIS_VALUES = listOf(40, 70, 180, 250, 400)
    private val Y_AXIS_LABEL_PRIORITY = listOf(70, 180, 40, 250, 400)
    private const val Y_AXIS_LABEL_MIN_SPACING_PX = 20f

    private val GRIDLINE_COLOR = Color.parseColor("#DDDDDD")
    private val AXIS_TEXT_COLOR = Color.parseColor("#666666")
    private val TARGET_BAND_COLOR = Color.parseColor("#1A3DDC97") // glucose_normal at low alpha
    private val MARKER_COLOR = Color.parseColor("#6B3FA0") // app's purple accent

    fun renderChart(data: ReportData, showAnnotations: Boolean, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val plotLeft = MARGIN_LEFT
        val plotRight = width - MARGIN_RIGHT
        val plotTop = MARGIN_TOP
        val plotBottom = height - MARGIN_BOTTOM

        val geometry = ChartGeometry(
            ChartRange(data.startDate, data.endDate),
            plotLeft, plotRight, plotTop, plotBottom, Y_MIN, Y_MAX
        )

        drawTargetBand(canvas, plotLeft, plotRight, geometry.yForValue(TARGET_HIGH), geometry.yForValue(TARGET_LOW))
        drawYAxis(canvas, plotLeft, plotRight, plotTop, plotBottom, geometry::yForValue)
        drawXAxis(canvas, data.startDate, data.endDate, plotLeft, plotRight, plotBottom, geometry::xForTime)
        drawGlucoseLine(canvas, data.readings, plotLeft, plotRight, geometry::xForTime, geometry::yForValue)

        if (showAnnotations) {
            // Below the x-axis date labels, not on the data line itself - a
            // doctor can see which point on the timeline an event lines up
            // with without it competing with the curve for attention.
            drawEventMarkers(canvas, buildEventMarkers(data.events), plotBottom + 46f, geometry::xForTime)
        }

        return bitmap
    }

    fun buildEventMarkers(events: List<UserEvent>): List<EventMarker> = EventMarkerLayout.buildEventMarkers(events)

    /** e.g. "1. Jul 2, 2:14pm — Pod Issue: site felt off" - the legend line
     *  format for the annotated PDF, matching buildEventMarkers' numbering.
     *  Date is included (not just time-of-day) since a report can span many
     *  days and time alone can't tell which one an event happened on. */
    fun legendLineFor(marker: EventMarker, zone: ZoneId = ZoneId.systemDefault()): String {
        val tag = EventTag.fromStorageValue(marker.event.tag)
        val instant = Instant.ofEpochMilli(marker.event.timestamp)
        val date = LEGEND_DATE_FORMATTER.withZone(zone).format(instant)
        val time = LEGEND_TIME_FORMATTER.withZone(zone).format(instant).lowercase().replace(" ", "")
        val notePart = marker.event.note?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
        return "${marker.number}. $date, $time — ${tag.label}$notePart"
    }

    private fun drawTargetBand(canvas: Canvas, left: Float, right: Float, topY: Float, bottomY: Float) {
        val paint = Paint().apply { color = TARGET_BAND_COLOR }
        canvas.drawRect(left, topY, right, bottomY, paint)
    }

    private fun drawYAxis(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, yForValue: (Float) -> Float) {
        val linePaint = Paint().apply { color = GRIDLINE_COLOR; strokeWidth = 1.5f }
        val textPaint = Paint().apply { color = AXIS_TEXT_COLOR; textSize = 22f; isAntiAlias = true }

        Y_AXIS_VALUES.forEach { value ->
            val y = yForValue(value.toFloat())
            canvas.drawLine(left, y, right, y, linePaint)
        }

        AxisTicks.yAxisLabels(Y_AXIS_VALUES, Y_AXIS_LABEL_PRIORITY, yForValue, Y_AXIS_LABEL_MIN_SPACING_PX)
            .filter { it.show }
            .forEach { label ->
                val y = yForValue(label.value.toFloat())
                canvas.drawText(label.value.toString(), 8f, y + 8f, textPaint)
            }
    }

    private fun drawXAxis(
        canvas: Canvas,
        start: Instant,
        end: Instant,
        left: Float,
        right: Float,
        bottom: Float,
        xForTime: (Instant) -> Float,
    ) {
        val textPaint = Paint().apply { color = AXIS_TEXT_COLOR; textSize = 22f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        AxisTicks.xAxisTicks(start, end).forEach { tick ->
            canvas.drawText(tick.label, xForTime(tick.instant), bottom + 24f, textPaint)
        }
    }

    /**
     * Splits into gap-free segments first (GapSegmenter), then buckets and
     * draws each segment independently - no line is ever drawn between two
     * segments, so a long report never implies data that isn't there. Within
     * a segment: buckets readings into fixed-width pixel columns and draws,
     * per bucket, a semi-transparent min-max band (so a cluster of fast
     * zone-crossing readings reads as one coherent shape instead of a tangle
     * of crossing, differently-colored segments) plus a solid line through
     * each bucket's average, both colored by the bucket average's severity
     * zone.
     */
    private fun drawGlucoseLine(
        canvas: Canvas,
        readings: List<GlucosePoint>,
        plotLeft: Float,
        plotRight: Float,
        xForTime: (Instant) -> Float,
        yForValue: (Float) -> Float,
    ) {
        GapSegmenter.segment(readings, MAX_CONNECT_GAP).forEach { segment ->
            drawSegment(canvas, segment, plotLeft, plotRight, xForTime, yForValue)
        }
    }

    private fun drawSegment(
        canvas: Canvas,
        readings: List<GlucosePoint>,
        plotLeft: Float,
        plotRight: Float,
        xForTime: (Instant) -> Float,
        yForValue: (Float) -> Float,
    ) {
        val bucketCount = ((plotRight - plotLeft) / BUCKET_WIDTH_PX).toInt().coerceAtLeast(1)
        val bucketMin = IntArray(bucketCount) { Int.MAX_VALUE }
        val bucketMax = IntArray(bucketCount) { Int.MIN_VALUE }
        val bucketSum = LongArray(bucketCount)
        val bucketCounts = IntArray(bucketCount)

        readings.forEach { point ->
            val x = xForTime(point.time)
            val bucket = (((x - plotLeft) / (plotRight - plotLeft)) * bucketCount).toInt().coerceIn(0, bucketCount - 1)
            bucketMin[bucket] = minOf(bucketMin[bucket], point.sgv)
            bucketMax[bucket] = maxOf(bucketMax[bucket], point.sgv)
            bucketSum[bucket] += point.sgv
            bucketCounts[bucket] += 1
        }

        val bandPaint = Paint().apply { style = Paint.Style.FILL }
        for (i in 0 until bucketCount) {
            if (bucketCounts[i] == 0 || bucketMax[i] == bucketMin[i]) continue
            val avg = (bucketSum[i] / bucketCounts[i]).toInt()
            val x = plotLeft + (i + 0.5f) / bucketCount * (plotRight - plotLeft)
            bandPaint.color = SeverityColoring.colorInt(avg)
            bandPaint.alpha = 90
            canvas.drawRect(
                x - BUCKET_WIDTH_PX / 2f, yForValue(bucketMax[i].toFloat()),
                x + BUCKET_WIDTH_PX / 2f, yForValue(bucketMin[i].toFloat()),
                bandPaint
            )
        }

        val linePaint = Paint().apply {
            strokeWidth = LINE_WIDTH
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        var prevX: Float? = null
        var prevY: Float? = null
        var prevAvg = 0
        for (i in 0 until bucketCount) {
            if (bucketCounts[i] == 0) continue
            val avg = (bucketSum[i] / bucketCounts[i]).toInt()
            val x = plotLeft + (i + 0.5f) / bucketCount * (plotRight - plotLeft)
            val y = yForValue(avg.toFloat())

            if (prevX != null) {
                linePaint.color = SeverityColoring.colorInt((prevAvg + avg) / 2)
                canvas.drawLine(prevX, prevY!!, x, y, linePaint)
            }
            prevX = x; prevY = y; prevAvg = avg
        }
    }

    /** Plain bold numbers in a row below the axis, no circle/background -
     *  deliberately unobtrusive so they read as a reference index rather than
     *  competing visually with the data line. Row/x-position comes from the
     *  shared EventMarkerLayout collision algorithm. */
    private fun drawEventMarkers(
        canvas: Canvas,
        markers: List<EventMarker>,
        baseRowY: Float,
        xForTime: (Instant) -> Float,
    ) {
        val numberPaint = Paint().apply {
            color = MARKER_COLOR
            textSize = 15f
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        EventMarkerLayout.layoutRows(markers, xForTime, MARKER_MIN_SPACING_PX, MARKER_MAX_ROWS).forEach { positioned ->
            val y = baseRowY + positioned.row * MARKER_ROW_HEIGHT_PX
            canvas.drawText(positioned.marker.number.toString(), positioned.x, y, numberPaint)
        }
    }

    val SEVERITY_LEGEND: List<Pair<String, Int>> = SeverityColoring.SEVERITY_LEGEND

    private val LEGEND_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d")
    private val LEGEND_TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")
}
