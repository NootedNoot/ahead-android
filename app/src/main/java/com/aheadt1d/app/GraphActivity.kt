package com.aheadt1d.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aheadt1d.app.chart.AxisTicks
import com.aheadt1d.app.chart.ChartDataSource
import com.aheadt1d.app.chart.ChartRange
import com.aheadt1d.app.chart.GapSegmenter
import com.aheadt1d.app.chart.SeverityColoring
import com.aheadt1d.app.events.EventCsvExporter
import com.aheadt1d.app.events.EventEditHelper
import com.aheadt1d.app.events.EventLogDialogs
import com.aheadt1d.app.events.EventTag
import com.aheadt1d.app.events.UserEvent
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.health.HealthConnectManager
import com.aheadt1d.app.report.ReportExportActivity
import com.aheadt1d.app.state.LatestTrendRepository
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.aheadt1d.ratemath.RateMath
import org.aheadt1d.ratemath.RatePoint
import org.aheadt1d.ratemath.TrajectoryKind

/**
 * Modern, dynamic full-screen glucose graph for Ahead.
 *
 * Features:
 *  - Dynamic time range selection (1h, 3h, 6h, 12h, 24h) with responsive segmented pill styling.
 *  - Real-time clinical summary metrics (Time in Range % and Average Glucose) computed per window.
 *  - Full-screen responsive layout without artificial aspect-ratio squishing.
 *  - RateMath predictive trajectory ("ghost line") in live mode.
 *  - Intelligent auto-scaling Y-axis snapped to 25 mg/dL increments.
 *  - Interactive point inspection (GlucoseMarkerView) and backdated event logging via long-press.
 *  - Seamless single-tap day-by-day navigation (◀ Day / Day ▶) and date range picker.
 */
class GraphActivity : AppCompatActivity() {

    private lateinit var chart: LineChart
    private lateinit var chartContainer: FrameLayout

    // Hero metric views
    private lateinit var heroGlucoseValueText: TextView
    private lateinit var heroTrendArrowText: TextView
    private lateinit var heroRateText: TextView
    private lateinit var heroTirBadge: TextView
    private lateinit var heroAvgText: TextView

    // Window selector buttons
    private lateinit var window1hButton: Button
    private lateinit var window3hButton: Button
    private lateinit var window6hButton: Button
    private lateinit var window12hButton: Button
    private lateinit var window24hButton: Button

    // Date navigation views
    private lateinit var prevDayButton: TextView
    private lateinit var nextDayButton: TextView
    private lateinit var dateRangeLabel: TextView
    private lateinit var dateSelectorContainer: View
    private lateinit var backToLiveButton: TextView

    private var cachedPoints: List<GlucosePoint> = emptyList()
    private var cachedEvents: List<UserEvent> = emptyList()
    private var selectedWindowMinutes = WINDOW_3H
    private val eventIconViews = mutableListOf<View>()
    private val axisTickViews = mutableListOf<View>()

    private var chartAnchor: Instant? = null
    private var lastVisibleEvents: List<UserEvent> = emptyList()
    private var lastHistoricalRange: ChartRange? = null

    private var refreshJob: kotlinx.coroutines.Job? = null

    // null = live (window-ending-at-now); non-null = viewing an arbitrary past range
    private var viewRange: ChartRange? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_graph)

        findViewById<android.widget.ImageButton>(R.id.graphBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.exportEventsButton).setOnClickListener { exportEvents() }
        findViewById<View>(R.id.doctorReportButton).setOnClickListener {
            startActivity(ReportExportActivity.createIntent(this))
        }
        findViewById<View>(R.id.notesHistoryButton).setOnClickListener {
            startActivity(com.aheadt1d.app.events.EventHistoryActivity.createIntent(this))
        }

        chart = findViewById(R.id.glucoseChart)
        chartContainer = findViewById(R.id.chartContainer)

        heroGlucoseValueText = findViewById(R.id.heroGlucoseValueText)
        heroTrendArrowText = findViewById(R.id.heroTrendArrowText)
        heroRateText = findViewById(R.id.heroRateText)
        heroTirBadge = findViewById(R.id.heroTirBadge)
        heroAvgText = findViewById(R.id.heroAvgText)

        window1hButton = findViewById(R.id.window1hButton)
        window3hButton = findViewById(R.id.window3hButton)
        window6hButton = findViewById(R.id.window6hButton)
        window12hButton = findViewById(R.id.window12hButton)
        window24hButton = findViewById(R.id.window24hButton)

        prevDayButton = findViewById(R.id.prevDayButton)
        nextDayButton = findViewById(R.id.nextDayButton)
        dateRangeLabel = findViewById(R.id.dateRangeLabel)
        dateSelectorContainer = findViewById(R.id.dateSelectorContainer)
        backToLiveButton = findViewById(R.id.backToLiveButton)

        loadWindowPrefs()
        setupChart()
        setupPointLongPress()

        window1hButton.setOnClickListener { selectWindow(WINDOW_1H) }
        window3hButton.setOnClickListener { selectWindow(WINDOW_3H) }
        window6hButton.setOnClickListener { selectWindow(WINDOW_6H) }
        window12hButton.setOnClickListener { selectWindow(WINDOW_12H) }
        window24hButton.setOnClickListener { selectWindow(WINDOW_24H) }

        prevDayButton.setOnClickListener { pageDay(forward = false) }
        nextDayButton.setOnClickListener { pageDay(forward = true) }
        dateSelectorContainer.setOnClickListener { openDateRangePicker() }
        backToLiveButton.setOnClickListener { backToLive() }

        updateWindowButtonStyles()
        updateDateRangeLabel()

        refreshChart()
        observeWorkerRuns()
        autoRefreshChart()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun observeWorkerRuns() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LatestTrendRepository.lastCheckedAt.collect { if (viewRange == null) refreshChart() }
            }
        }
    }

    private fun autoRefreshChart() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(CHART_AUTO_REFRESH_MS)
                    if (viewRange == null) refreshChart()
                }
            }
        }
    }

    private fun refreshChart() {
        if (!HealthConnectManager.isAvailable(this)) return
        val now = Instant.now()
        val range = viewRange ?: ChartRange(now.minus(Duration.ofMinutes(selectedWindowMinutes)), now)

        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val data = ChartDataSource.load(applicationContext, range)
            cachedPoints = data.readings
            cachedEvents = data.events
            renderChart()
        }
    }

    private fun exportEvents() {
        lifecycleScope.launch {
            val intent = EventCsvExporter.export(applicationContext)
            startActivity(Intent.createChooser(intent, getString(R.string.export_events_title)))
        }
    }

    private fun selectWindow(minutes: Long) {
        selectedWindowMinutes = minutes
        saveWindowPrefs()
        viewRange = null
        updateWindowButtonStyles()
        updateDateRangeLabel()
        refreshChart()
    }

    private fun updateWindowButtonStyles() {
        setButtonActive(window1hButton, selectedWindowMinutes == WINDOW_1H)
        setButtonActive(window3hButton, selectedWindowMinutes == WINDOW_3H)
        setButtonActive(window6hButton, selectedWindowMinutes == WINDOW_6H)
        setButtonActive(window12hButton, selectedWindowMinutes == WINDOW_12H)
        setButtonActive(window24hButton, selectedWindowMinutes == WINDOW_24H)
    }

    private fun setButtonActive(button: Button, active: Boolean) {
        button.setBackgroundResource(if (active) R.drawable.time_btn_active else R.drawable.time_btn_inactive)
        button.setTextColor(ContextCompat.getColor(this, if (active) R.color.accent2 else R.color.muted))
    }

    private fun loadWindowPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        selectedWindowMinutes = prefs.getLong(KEY_WINDOW_MINUTES, WINDOW_3H)
    }

    private fun saveWindowPrefs() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putLong(KEY_WINDOW_MINUTES, selectedWindowMinutes)
            .apply()
    }

    private fun setupChart() {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.setDrawGridBackground(false)
        chart.setPinchZoom(true)
        chart.isDoubleTapToZoomEnabled = false
        chart.isDragEnabled = true
        chart.isScaleXEnabled = true
        chart.isScaleYEnabled = false
        chart.axisRight.isEnabled = false
        chart.isDragDecelerationEnabled = false

        val mutedColor = ContextCompat.getColor(this, R.color.muted)
        val borderColor = ContextCompat.getColor(this, R.color.border)

        chart.setNoDataTextColor(mutedColor)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = borderColor
            textColor = mutedColor
            textSize = 10f
            setDrawAxisLine(false)
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = borderColor
            textColor = mutedColor
            textSize = 10f
            setDrawAxisLine(false)
            granularity = 25f
            isGranularityEnabled = true
        }
    }

    private fun setupPointLongPress() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                handleChartLongPress(e.x, e.y)
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY) * 1.5f) return false
                if (kotlin.math.abs(velocityX) < FLING_VELOCITY_THRESHOLD) return false
                pageDay(forward = velocityX > 0)
                return true
            }
        })
        chart.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        chart.onChartGestureListener = object : com.github.mikephil.charting.listener.OnChartGestureListener {
            override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture?) {}
            override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: com.github.mikephil.charting.listener.ChartTouchListener.ChartGesture?) { repositionOverlays() }
            override fun onChartLongPressed(me: MotionEvent?) {}
            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartSingleTapped(me: MotionEvent?) {}
            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
            override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) { repositionOverlays() }
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) { repositionOverlays() }
        }
    }

    private fun repositionOverlays() {
        val anchor = chartAnchor ?: return
        clearEventIcons()
        placeEventIcons(lastVisibleEvents, anchor)
        val range = lastHistoricalRange
        if (range != null) {
            clearAxisTickLabels()
            placeAxisTickLabels(range.start, range.end, anchor, ZoneId.systemDefault())
        }
    }

    private fun pageDay(forward: Boolean) {
        val zone = ZoneId.systemDefault()
        val currentStartDate = viewRange?.start?.atZone(zone)?.toLocalDate() ?: LocalDate.now(zone).minusDays(1)
        val newStartDate = if (forward) currentStartDate.plusDays(1) else currentStartDate.minusDays(1)

        if (!newStartDate.isBefore(LocalDate.now(zone))) {
            backToLive()
            return
        }

        viewRange = ChartRange(
            newStartDate.atStartOfDay(zone).toInstant(),
            newStartDate.plusDays(1).atStartOfDay(zone).toInstant()
        )
        updateDateRangeLabel()
        refreshChart()
    }

    private fun openDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.graph_pick_date_range))
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val startMillis = selection.first
            val endMillis = selection.second
            viewRange = ChartRange(
                Instant.ofEpochMilli(startMillis),
                Instant.ofEpochMilli(endMillis).plusSeconds(24 * 3600 - 1)
            )
            updateDateRangeLabel()
            refreshChart()
        }
        picker.show(supportFragmentManager, "graph_date_range")
    }

    private fun backToLive() {
        viewRange = null
        updateDateRangeLabel()
        refreshChart()
    }

    private fun updateDateRangeLabel() {
        val range = viewRange
        if (range == null) {
            dateRangeLabel.text = "● Live"
            dateRangeLabel.setTextColor(ContextCompat.getColor(this, R.color.ok))
            backToLiveButton.visibility = View.GONE
        } else {
            val zone = ZoneId.systemDefault()
            val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(zone)
            val startDate = range.start.atZone(zone).toLocalDate()
            val endDate = range.end.minusSeconds(1).atZone(zone).toLocalDate()
            dateRangeLabel.text = if (startDate == endDate) {
                formatter.format(range.start)
            } else {
                "${formatter.format(range.start)} – ${formatter.format(range.end.minusSeconds(1))}"
            }
            dateRangeLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            backToLiveButton.visibility = View.VISIBLE
        }
    }

    private fun handleChartLongPress(touchX: Float, touchY: Float) {
        val anchor = chartAnchor ?: return
        val dataSets = chart.data?.dataSets.orEmpty()
        if (dataSets.isEmpty()) return
        val transformer = chart.getTransformer(YAxis.AxisDependency.LEFT)
        val hitRadiusPx = dp(24)

        var nearestEntry: Entry? = null
        var nearestDistSq = Float.MAX_VALUE
        dataSets.forEach { dataSet ->
            for (i in 0 until dataSet.entryCount) {
                val entry = dataSet.getEntryForIndex(i)
                val pixel = transformer.getPixelForValues(entry.x, entry.y)
                val dx = (pixel.x - touchX).toFloat()
                val dy = (pixel.y - touchY).toFloat()
                val distSq = dx * dx + dy * dy
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq
                    nearestEntry = entry
                }
            }
        }

        val entry = nearestEntry ?: return
        if (nearestDistSq > (hitRadiusPx * hitRadiusPx).toFloat()) return

        chart.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val timestamp = anchor.plusSeconds((entry.x * 60).toLong()).toEpochMilli()
        EventLogDialogs.showPresetPicker(
            this,
            lifecycleScope,
            EventLogDialogs.LoggedPointContext(timestamp = timestamp, glucoseValue = entry.y)
        )
    }

    /** Updates the hero card with current reading, trend arrow, rate, TIR %, and average. */
    private fun updateHeroStats(points: List<GlucosePoint>) {
        if (points.isEmpty()) {
            heroGlucoseValueText.text = "--"
            heroGlucoseValueText.setTextColor(ContextCompat.getColor(this, R.color.muted))
            heroTrendArrowText.text = ""
            heroRateText.text = "No data"
            heroTirBadge.text = "--% in Range"
            heroAvgText.text = "Avg: -- mg/dL"
            return
        }

        val latest = points.last()
        heroGlucoseValueText.text = latest.sgv.toString()
        heroGlucoseValueText.setTextColor(SeverityColoring.colorInt(latest.sgv))

        val trend = LatestTrendRepository.latestTrend.value
        if (trend != null && viewRange == null) {
            val arrow = com.aheadt1d.app.notifications.GlucoseTrendArrow.fromRatePerMinute(trend.rate)
            heroTrendArrowText.text = arrow.label
            val rateVal = trend.rate
            val rateFormatted = if (rateVal != null) String.format(Locale.US, "%+.1f/m", rateVal) else "Live"
            heroRateText.text = rateFormatted
        } else {
            heroTrendArrowText.text = ""
            heroRateText.text = if (viewRange == null) "Live" else "Historical"
        }

        val inRangeCount = points.count { it.sgv in 70..180 }
        val tirPercent = (inRangeCount * 100) / points.size
        val avgGlucose = points.map { it.sgv }.average().toInt()

        heroTirBadge.text = "$tirPercent% in Range"
        val tirColor = when {
            tirPercent >= 70 -> ContextCompat.getColor(this, R.color.ok)
            tirPercent >= 50 -> ContextCompat.getColor(this, R.color.high)
            else -> ContextCompat.getColor(this, R.color.low)
        }
        heroTirBadge.setTextColor(tirColor)
        heroAvgText.text = "Avg: $avgGlucose mg/dL"
    }

    /**
     * Smart dynamic Y-axis bounds: guaranteed to comfortably frame the 70-180 mg/dL target zone,
     * while cleanly expanding to fit highs or lows, snapped to clean 25 mg/dL grid increments.
     */
    private fun applyDynamicYAxisRange(windowed: List<GlucosePoint>) {
        if (windowed.isEmpty()) {
            chart.axisLeft.axisMinimum = 40f
            chart.axisLeft.axisMaximum = 250f
            return
        }
        val lo = windowed.minOf { it.sgv }.toFloat()
        val hi = windowed.maxOf { it.sgv }.toFloat()

        val desiredMin = (lo - 20f).coerceAtMost(60f).coerceAtLeast(30f)
        val desiredMax = (hi + 25f).coerceAtLeast(200f).coerceAtMost(400f)

        chart.axisLeft.axisMinimum = floor(desiredMin / 25f) * 25f
        chart.axisLeft.axisMaximum = ceil(desiredMax / 25f) * 25f
    }

    private fun renderChart() {
        val range = viewRange
        val now = Instant.now()
        val zone = ZoneId.systemDefault()

        val windowStart: Instant
        val windowEnd: Instant
        val windowed: List<GlucosePoint>
        if (range == null) {
            val cutoff = now.minus(Duration.ofMinutes(selectedWindowMinutes))
            windowStart = cutoff
            windowEnd = now
            windowed = cachedPoints.filter { it.time.isAfter(cutoff) }
        } else {
            windowStart = range.start
            windowEnd = range.end
            windowed = cachedPoints
        }

        updateHeroStats(windowed)
        clearEventIcons()
        clearAxisTickLabels()

        if (windowed.isEmpty()) {
            val label = if (range == null) windowLabel(selectedWindowMinutes) else dateRangeLabel.text.toString()
            chart.setNoDataText(
                if (range == null) getString(R.string.chart_no_data, label)
                else getString(R.string.graph_no_data_for_range, label)
            )
            chart.clear()
            return
        }

        applyDynamicYAxisRange(windowed)

        val anchor = windowStart.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant()
        chartAnchor = anchor

        val baseDataSets = GapSegmenter.segment(windowed).map { segment ->
            val entries = segment.map { point -> Entry(minutesFromAnchor(anchor, point.time), point.sgv.toFloat()) }
            val pointColors = segment.map { SeverityColoring.colorInt(it.sgv) }
            LineDataSet(entries, "Glucose").apply {
                color = ContextCompat.getColor(this@GraphActivity, R.color.accent)
                lineWidth = 2.5f
                setDrawCircleHole(false)
                circleRadius = 3.5f
                setCircleColors(pointColors)
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
                highLightColor = ContextCompat.getColor(this@GraphActivity, R.color.accent2)
                setDrawHorizontalHighlightIndicator(false)
                setDrawVerticalHighlightIndicator(false)
            }
        }

        val allDataSets = mutableListOf<ILineDataSet>()
        allDataSets.addAll(baseDataSets)

        var axisMax = minutesFromAnchor(anchor, windowEnd)

        // RateMath Predictive Trajectory ("Ghost Line") in Live Mode
        if (range == null && windowed.isNotEmpty()) {
            val ghostEntries = buildGhostLineEntries(windowed, anchor)
            if (ghostEntries.isNotEmpty()) {
                allDataSets.add(ghostLineDataSet(ghostEntries))
                axisMax = ghostEntries.last().x
            }
        }

        // Tapping a point shows exact value + timestamp in callout
        chart.marker = GlucoseMarkerView(this, anchor, zone)

        // Threshold lines: 70 mg/dL (Low) and 180 mg/dL (High)
        chart.axisLeft.removeAllLimitLines()
        chart.axisLeft.addLimitLine(LimitLine(70f, getString(R.string.chart_low_threshold_label)).apply {
            lineColor = ContextCompat.getColor(this@GraphActivity, R.color.low)
            textColor = ContextCompat.getColor(this@GraphActivity, R.color.low)
            textSize = 10f
            lineWidth = 1f
            labelPosition = LimitLine.LimitLabelPosition.LEFT_BOTTOM
            enableDashedLine(10f, 6f, 0f)
        })
        chart.axisLeft.addLimitLine(LimitLine(180f, getString(R.string.chart_high_threshold_label)).apply {
            lineColor = ContextCompat.getColor(this@GraphActivity, R.color.high)
            textColor = ContextCompat.getColor(this@GraphActivity, R.color.high)
            textSize = 10f
            lineWidth = 1f
            labelPosition = LimitLine.LimitLabelPosition.LEFT_TOP
            enableDashedLine(10f, 6f, 0f)
        })

        chart.xAxis.removeAllLimitLines()
        if (range == null) {
            chart.xAxis.addLimitLine(LimitLine(minutesFromAnchor(anchor, now), getString(R.string.chart_now_label)).apply {
                lineColor = ContextCompat.getColor(this@GraphActivity, R.color.muted)
                textColor = ContextCompat.getColor(this@GraphActivity, R.color.muted)
                textSize = 10f
                lineWidth = 1f
                labelPosition = LimitLine.LimitLabelPosition.RIGHT_BOTTOM
                enableDashedLine(6f, 6f, 0f)
            })
        }

        val visibleEvents = cachedEvents.filter { it.timestamp in windowStart.toEpochMilli()..windowEnd.toEpochMilli() }
        lastVisibleEvents = visibleEvents
        lastHistoricalRange = range

        chart.xAxis.apply {
            axisMinimum = minutesFromAnchor(anchor, windowStart)
            axisMaximum = axisMax
            if (range == null) {
                setDrawLabels(true)
                granularity = axisGranularityMinutes(selectedWindowMinutes)
                isGranularityEnabled = true
                setLabelCount(axisLabelCount(selectedWindowMinutes), true)
                valueFormatter = HourAxisFormatter(anchor, zone)
            } else {
                setDrawLabels(false)
            }
        }

        chart.highlightValues(null)
        chart.data = LineData(allDataSets)
        chart.notifyDataSetChanged()
        chart.fitScreen()
        chart.invalidate()

        chart.post {
            placeEventIcons(visibleEvents, anchor)
            if (range != null) placeAxisTickLabels(windowStart, windowEnd, anchor, zone)
        }
    }

    /**
     * Builds the RateMath predictive ghost trajectory into the next 20-30 minutes.
     */
    private fun buildGhostLineEntries(windowed: List<GlucosePoint>, anchor: Instant): List<Entry> {
        val last = windowed.lastOrNull() ?: return emptyList()
        val ratePoints = windowed.map { RatePoint(it.time.toEpochMilli(), it.sgv) }
        val rates = RateMath.recentRates(ratePoints, GHOST_RATE_SAMPLES)
        val currentRate = rates.lastOrNull() ?: return emptyList()
        val trajectory = RateMath.assessRateTrajectory(rates)
        val decayPerStep = if (trajectory.kind == TrajectoryKind.DECELERATING) {
            trajectory.avgDeltaPerStep
        } else {
            0.0
        }
        val decayed = RateMath.projectWithDecay(last.sgv, currentRate, decayPerStep, GHOST_PROJECTION_MINUTES)

        val entries = mutableListOf(Entry(minutesFromAnchor(anchor, last.time), last.sgv.toFloat()))
        decayed.forEach { point ->
            val t = last.time.plusSeconds(point.minutesAhead * 60L)
            entries.add(Entry(minutesFromAnchor(anchor, t), point.value.toFloat()))
        }
        return entries
    }

    private fun ghostLineDataSet(entries: List<Entry>): LineDataSet = LineDataSet(entries, "Projected").apply {
        color = ColorUtils.setAlphaComponent(ContextCompat.getColor(this@GraphActivity, R.color.accent2), 120)
        lineWidth = 2f
        enableDashedLine(12f, 8f, 0f)
        setDrawCircles(false)
        setDrawValues(false)
        mode = LineDataSet.Mode.LINEAR
    }

    private fun placeEventIcons(events: List<UserEvent>, anchor: Instant) {
        val transformer = chart.getTransformer(YAxis.AxisDependency.LEFT)
        val topOffsetPx = chart.top + chart.viewPortHandler.offsetTop().toInt() + dp(2)
        val iconSizePx = dp(22)
        val nowPixelX = transformer.getPixelForValues(minutesFromAnchor(anchor, Instant.now()), chart.axisLeft.axisMaximum).x
        events.forEach { event ->
            val xValue = minutesFromAnchor(anchor, Instant.ofEpochMilli(event.timestamp))
            val point = transformer.getPixelForValues(xValue, chart.axisLeft.axisMaximum)
            var iconCenterX = point.x
            if (viewRange == null && kotlin.math.abs(iconCenterX - nowPixelX) < iconSizePx) {
                iconCenterX -= iconSizePx.toDouble()
            }
            val tag = EventTag.fromStorageValue(event.tag)
            val icon = TextView(this).apply {
                text = tag.glyph
                textSize = 14f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.marker_background)
                setOnClickListener { EventEditHelper.show(this@GraphActivity, event, onSaved = { refreshChart() }, onDeleted = { refreshChart() }) }
            }
            val params = FrameLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                leftMargin = (iconCenterX - iconSizePx / 2).toInt().coerceAtLeast(0)
                topMargin = topOffsetPx
            }
            chartContainer.addView(icon, params)
            eventIconViews.add(icon)
        }
    }

    private fun clearEventIcons() {
        eventIconViews.forEach { chartContainer.removeView(it) }
        eventIconViews.clear()
    }

    private fun placeAxisTickLabels(start: Instant, end: Instant, anchor: Instant, zone: ZoneId) {
        val transformer = chart.getTransformer(YAxis.AxisDependency.LEFT)
        val bottomY = chart.top + chart.viewPortHandler.contentBottom().toInt() + dp(2)
        val mutedColor = ContextCompat.getColor(this, R.color.muted)
        val measurePaint = android.graphics.Paint().apply { textSize = 11f * resources.displayMetrics.scaledDensity }

        val maxLeft = (chartContainer.width - 1).toFloat()
        var lastLabelRight = Float.NEGATIVE_INFINITY
        AxisTicks.xAxisTicks(start, end, zone).forEach { tick ->
            val xValue = minutesFromAnchor(anchor, tick.instant)
            val point = transformer.getPixelForValues(xValue, chart.axisLeft.axisMinimum)
            val textWidth = measurePaint.measureText(tick.label)
            val left = (point.x.toFloat() - textWidth / 2f).coerceIn(0f, (maxLeft - textWidth).coerceAtLeast(0f))
            if (left < lastLabelRight + TICK_LABEL_MIN_GAP_PX) return@forEach
            lastLabelRight = left + textWidth

            val label = TextView(this).apply {
                text = tick.label
                textSize = 11f
                setTextColor(mutedColor)
            }
            chartContainer.addView(
                label,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = left.toInt()
                    topMargin = bottomY
                }
            )
            axisTickViews.add(label)
        }
    }

    private fun clearAxisTickLabels() {
        axisTickViews.forEach { chartContainer.removeView(it) }
        axisTickViews.clear()
    }

    private fun minutesFromAnchor(anchor: Instant, instant: Instant): Float =
        Duration.between(anchor, instant).toMillis() / 60_000f

    private fun axisGranularityMinutes(windowMinutes: Long): Float = when (windowMinutes) {
        WINDOW_1H -> 15f
        WINDOW_3H -> 30f
        WINDOW_6H -> 60f
        WINDOW_12H -> 120f
        else -> 240f
    }

    private fun axisLabelCount(windowMinutes: Long): Int = when (windowMinutes) {
        WINDOW_1H -> 5
        WINDOW_3H -> 7
        WINDOW_6H -> 7
        WINDOW_12H -> 7
        else -> 7
    }

    private fun windowLabel(windowMinutes: Long): String = when (windowMinutes) {
        WINDOW_1H -> "1h"
        WINDOW_3H -> "3h"
        WINDOW_6H -> "6h"
        WINDOW_12H -> "12h"
        else -> "24h"
    }

    private class HourAxisFormatter(
        private val anchor: Instant,
        private val zone: ZoneId
    ) : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val zdt = anchor.plusSeconds((value * 60).toLong()).atZone(zone)
            return if (zdt.minute == 0) HOUR_FORMATTER.format(zdt) else MINUTE_FORMATTER.format(zdt)
        }

        companion object {
            private val HOUR_FORMATTER = DateTimeFormatter.ofPattern("ha")
            private val MINUTE_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")
        }
    }

    companion object {
        private const val WINDOW_1H = 60L
        private const val WINDOW_3H = 180L
        private const val WINDOW_6H = 360L
        private const val WINDOW_12H = 720L
        private const val WINDOW_24H = 1440L

        private const val GHOST_RATE_SAMPLES = 4
        private const val GHOST_PROJECTION_MINUTES = 25

        private const val CHART_AUTO_REFRESH_MS = 5 * 60 * 1000L
        private const val PREFS_NAME = "ahead_graph_settings"
        private const val KEY_WINDOW_MINUTES = "selected_window_minutes"
        private const val FLING_VELOCITY_THRESHOLD = 800f
        private const val TICK_LABEL_MIN_GAP_PX = 16f

        fun createIntent(context: Context): Intent = Intent(context, GraphActivity::class.java)
    }
}
