package com.aheadt1d.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aheadt1d.app.chart.AxisTicks
import com.aheadt1d.app.chart.ChartDataSource
import com.aheadt1d.app.chart.ChartRange
import com.aheadt1d.app.chart.GapSegmenter
import com.aheadt1d.app.chart.RangeMode
import com.aheadt1d.app.chart.SeverityColoring
import com.aheadt1d.app.events.EventCsvExporter
import com.aheadt1d.app.events.EventEditHelper
import com.aheadt1d.app.events.EventLogDialogs
import com.aheadt1d.app.events.EventTag
import com.aheadt1d.app.events.UserEvent
import com.aheadt1d.app.events.UserEventRepository
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.health.HealthConnectManager
import com.aheadt1d.app.report.ReportExportActivity
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.ui.GlucoseBucket
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen glucose graph, reached from the home dashboard's GLUCOSE TREND
 * card (a real back-stack entry, not a dialog). Deliberately reads from the
 * same HealthConnectManager/LatestTrendRepository sources as MainActivity's
 * card instead of any new polling loop - this screen is just a bigger view
 * onto the same on-device data.
 *
 * Two modes:
 *  - Live ([viewRange] == null): the original 1h/3h/6h window ending at
 *    "now", auto-refreshing.
 *  - Historical ([viewRange] != null): an arbitrary past [ChartRange] picked
 *    via swipe-paging or the date-range picker, no auto-refresh, no "now"
 *    marker. All data fetching (live or historical) goes through the shared
 *    ChartDataSource so this screen, the doctor report, and (soon) the
 *    interactive export can never disagree about what a given range
 *    contains.
 */
class GraphActivity : AppCompatActivity() {

    private lateinit var chart: LineChart
    private lateinit var chartContainer: FrameLayout
    private lateinit var legendContainer: LinearLayout
    private lateinit var window1hButton: Button
    private lateinit var window3hButton: Button
    private lateinit var window6hButton: Button
    private lateinit var rangeTightButton: Button
    private lateinit var rangeFullButton: Button
    private lateinit var rangeAutoButton: Button
    private lateinit var dateRangeLabel: TextView
    private lateinit var pickDateRangeButton: Button
    private lateinit var backToLiveButton: Button

    private var cachedPoints: List<GlucosePoint> = emptyList()
    private var cachedEvents: List<UserEvent> = emptyList()
    private var selectedWindowMinutes = WINDOW_1H
    private val eventIconViews = mutableListOf<View>()
    private val axisTickViews = mutableListOf<View>()

    // Set at the top of every renderChart() call - needed to convert a
    // long-pressed pixel's Entry.x (minutes-from-anchor) back into a real
    // Instant for the backdated event log.
    private var chartAnchor: Instant? = null

    // Snapshot of what the overlays (event icons, historical-mode axis tick
    // labels) were last drawn from - event icons and tick labels are plain
    // absolute-positioned Views computed once per renderChart() call from the
    // chart's pixel transform at that instant. MPAndroidChart's own
    // pinch-zoom/pan is still enabled for scrubbing within the loaded window,
    // and it does NOT re-run our overlay code on its own - left unhandled,
    // panning/zooming natively (not the day-paging fling) leaves these
    // overlays stuck at their pre-gesture screen position while the
    // underlying curve moves under them, so they end up visually detached
    // from (or entirely off) the data they're meant to mark. repositionOverlays()
    // (wired to the chart's gesture listener below) redraws them from this
    // snapshot on every pan/zoom step, with no data refetch needed.
    private var lastVisibleEvents: List<UserEvent> = emptyList()
    private var lastHistoricalRange: ChartRange? = null

    private var refreshJob: kotlinx.coroutines.Job? = null

    // null = live (window-ending-at-now); non-null = viewing an arbitrary
    // past range, set via swipe-paging or the date-range picker.
    private var viewRange: ChartRange? = null

    private var rangeMode = RangeMode.FULL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_graph)

        findViewById<android.widget.ImageButton>(R.id.graphBackButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.exportEventsButton).setOnClickListener { exportEvents() }
        findViewById<Button>(R.id.doctorReportButton).setOnClickListener {
            startActivity(ReportExportActivity.createIntent(this))
        }
        findViewById<Button>(R.id.notesHistoryButton).setOnClickListener {
            startActivity(com.aheadt1d.app.events.EventHistoryActivity.createIntent(this))
        }

        chart = findViewById(R.id.glucoseChart)
        chartContainer = findViewById(R.id.chartContainer)
        legendContainer = findViewById(R.id.legendContainer)
        window1hButton = findViewById(R.id.window1hButton)
        window3hButton = findViewById(R.id.window3hButton)
        window6hButton = findViewById(R.id.window6hButton)
        rangeTightButton = findViewById(R.id.rangeTightButton)
        rangeFullButton = findViewById(R.id.rangeFullButton)
        rangeAutoButton = findViewById(R.id.rangeAutoButton)
        dateRangeLabel = findViewById(R.id.dateRangeLabel)
        pickDateRangeButton = findViewById(R.id.pickDateRangeButton)
        backToLiveButton = findViewById(R.id.backToLiveButton)

        loadRangePrefs()
        setupChart()
        setupPointLongPress()
        setupAspectRatioCap()
        buildLegend()
        window1hButton.setOnClickListener { selectWindow(WINDOW_1H) }
        window3hButton.setOnClickListener { selectWindow(WINDOW_3H) }
        window6hButton.setOnClickListener { selectWindow(WINDOW_6H) }
        rangeTightButton.setOnClickListener { setRangeMode(RangeMode.TIGHT) }
        rangeFullButton.setOnClickListener { setRangeMode(RangeMode.FULL) }
        rangeAutoButton.setOnClickListener { setRangeMode(RangeMode.AUTO) }
        pickDateRangeButton.setOnClickListener { openDateRangePicker() }
        backToLiveButton.setOnClickListener { backToLive() }
        updateWindowButtonStyles()
        updateRangeButtonStyles()
        updateDateRangeLabel()

        refreshChart()
        observeWorkerRuns()
        autoRefreshChart()
    }

    /**
     * Caps the chart's height to a landscape-leaning aspect ratio (width *
     * ASPECT_RATIO) instead of letting it stretch to fill whatever vertical
     * space the screen happens to have left. On a tall phone that stretch is
     * what made ordinary glucose swings look artificially steep - a wider,
     * shorter frame reads calmer for the same data. Horizontal pan/zoom
     * (already enabled in setupChart) still covers long time windows within
     * that frame. Runs once after the container's first layout pass, since
     * its width isn't known before then.
     */
    private fun setupAspectRatioCap() {
        chartContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                chartContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val width = chartContainer.width
                val containerHeight = chartContainer.height
                if (width <= 0 || containerHeight <= 0) return
                val desiredHeight = (width * ASPECT_RATIO).toInt().coerceAtMost(containerHeight)
                chart.layoutParams = (chart.layoutParams as FrameLayout.LayoutParams).apply {
                    height = desiredHeight
                    gravity = Gravity.CENTER
                }
            }
        })
    }

    /** Same severity ladder as everywhere else (GlucoseSeverity) - a dot on
     *  the curve and a legend swatch can never disagree about what a colour
     *  means. HIGH and CRITICAL_HIGH share the display label "HIGH", so only
     *  the first (lighter) one is shown to keep the row from listing "HIGH"
     *  twice. */
    private fun buildLegend() {
        GlucoseBucket.entries.distinctBy { it.label }.forEach { bucket ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(5) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@GraphActivity, bucket.colorRes))
                }
            }
            val label = TextView(this).apply {
                text = bucket.label
                setTextColor(ContextCompat.getColor(this@GraphActivity, R.color.muted))
                textSize = 10f
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10); marginEnd = dp(10) }
            }
            row.addView(dot)
            row.addView(label)
            legendContainer.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // Same trigger MainActivity uses: a fresh Worker run means new Health
    // Connect data may have landed, so re-fetch. Only while live - a
    // historical view shouldn't jump back to "now" just because a worker
    // tick fired in the background.
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
        val range = viewRange ?: ChartRange(Instant.now().minus(Duration.ofMinutes(WINDOW_6H)), Instant.now())
        // Cancel any still-in-flight fetch from a previous refreshChart() call
        // before starting a new one - swiping/paging quickly (back a day,
        // then immediately forward again) fires refreshChart() repeatedly,
        // and without this an earlier, slower request (e.g. querying a wider
        // historical range) can finish AFTER a later, faster one and
        // overwrite cachedPoints/cachedEvents with stale data - rendered
        // against the CURRENT viewRange, which no longer matches what was
        // actually fetched. That mismatch is exactly what silently drops
        // event icons: the stale events don't fall within the now-current
        // window's filter.
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
        // The 1h/3h/6h buttons are a live-view concept - picking one always
        // returns to live, same as backToLiveButton.
        viewRange = null
        updateWindowButtonStyles()
        updateDateRangeLabel()
        refreshChart()
    }

    private fun updateWindowButtonStyles() {
        setButtonActive(window1hButton, selectedWindowMinutes == WINDOW_1H)
        setButtonActive(window3hButton, selectedWindowMinutes == WINDOW_3H)
        setButtonActive(window6hButton, selectedWindowMinutes == WINDOW_6H)
    }

    private fun setButtonActive(button: Button, active: Boolean) {
        button.setBackgroundResource(if (active) R.drawable.time_btn_active else R.drawable.time_btn_inactive)
        button.setTextColor(ContextCompat.getColor(this, if (active) R.color.accent2 else R.color.muted))
    }

    private fun setRangeMode(mode: RangeMode) {
        rangeMode = mode
        saveRangePrefs()
        updateRangeButtonStyles()
        renderChart()
    }

    private fun updateRangeButtonStyles() {
        setButtonActive(rangeTightButton, rangeMode == RangeMode.TIGHT)
        setButtonActive(rangeFullButton, rangeMode == RangeMode.FULL)
        setButtonActive(rangeAutoButton, rangeMode == RangeMode.AUTO)
    }

    /** TIGHT/FULL/AUTO all produce genuinely different bounds - AUTO's fit-to-
     *  data bounds are snapped to the same 25 mg/dL grid the fixed TIGHT/FULL
     *  bounds already land on by construction (70/180/40/400 are all
     *  multiples of 25), so gridlines/labels stay locked to clean numbers
     *  (50/75/100/125/150...) instead of drifting to whatever the data's
     *  min/max happens to be. */
    private fun applyYAxisRange(windowed: List<GlucosePoint>) {
        val (minY, maxY) = rangeMode.yBounds(windowed)
        chart.axisLeft.axisMinimum = minY
        chart.axisLeft.axisMaximum = maxY
    }

    private fun loadRangePrefs() {
        // Shares MainActivity's prefs file/key, so a range choice made on
        // either screen is remembered on the other.
        val prefs = getSharedPreferences(RANGE_PREFS_NAME, MODE_PRIVATE)
        rangeMode = runCatching { RangeMode.valueOf(prefs.getString(KEY_RANGE_MODE, null) ?: "") }
            .getOrDefault(RangeMode.FULL)
    }

    private fun saveRangePrefs() {
        getSharedPreferences(RANGE_PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_RANGE_MODE, rangeMode.name)
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
        // A day-paging swipe is the same touch gesture MPAndroidChart's own
        // drag handling sees, so it also kicks off the chart's native
        // momentum/deceleration animation - that keeps adjusting the
        // viewport for several frames after touch-up, running well past the
        // single frame our post{}-scheduled icon placement waits for. The
        // icons end up positioned against a transform that's still settling,
        // so they land wrong (often off the visible plot) until some later,
        // unrelated touch re-triggers repositionOverlays() once the chart
        // has actually stopped moving. Disabling residual momentum makes the
        // viewport settle immediately at touch-up, removing that race.
        chart.isDragDecelerationEnabled = false

        val mutedColor = ContextCompat.getColor(this, R.color.muted)
        val borderColor = ContextCompat.getColor(this, R.color.border)

        chart.setNoDataTextColor(mutedColor)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = borderColor
            textColor = mutedColor
            textSize = 11f
            setDrawAxisLine(false)
            // granularity/labelCount are set per-render (axisGranularityMinutes/
            // axisLabelCount) since the right density depends on the selected
            // window, not a single fixed value for every zoom level.
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = borderColor
            textColor = mutedColor
            textSize = 11f
            setDrawAxisLine(false)
            // Explicit mg/dL gridline spacing (every 25) so the axis reads as
            // real gridlines with labels, not just a floating trend line -
            // and, combined with applyYAxisRange's grid-snapped AUTO bounds,
            // gridlines always land on the same 50/75/100/125/150-style
            // numbers regardless of window/range mode.
            granularity = RangeMode.GRID_STEP
            isGranularityEnabled = true
        }
    }

    /** Long-press on (or near) a plotted point opens the same "Log an event"
     *  picker the home screen's FAB uses, but pre-filled with that point's
     *  timestamp/value instead of "now" - for logging something after the
     *  fact, at the moment it actually happened on the curve. A horizontal
     *  fling instead pages the visible window back/forward a day (swipe left
     *  = back, swipe right = forward, per spec). Both gestures only
     *  *observe* touches (onTouch always returns false), so MPAndroidChart's
     *  own pan/pinch-zoom handling on the same view is unaffected. */
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

        // Event icons and (in historical mode) axis tick labels are overlay
        // Views positioned once from the chart's pixel transform when they're
        // drawn - MPAndroidChart's own pinch-zoom/pan (still enabled for
        // scrubbing within the loaded window) moves the curve under them
        // without ever telling this code to recompute, so without this
        // listener the overlays visually drift away from - or entirely off
        // of - the data they're marking as soon as the user pans/zooms.
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

    /** Redraws the event-icon (and, in historical mode, axis-tick-label)
     *  overlays from the last-loaded data and the chart's *current* pixel
     *  transform - called after every native pan/zoom step so the overlays
     *  track the curve instead of staying stuck at their pre-gesture screen
     *  position. No data refetch: same [lastVisibleEvents]/[chartAnchor]
     *  renderChart() already computed. */
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

    /** Shifts [viewRange] by one full calendar day and reloads. From live
     *  mode, swiping back enters historical mode at yesterday (a full local
     *  midnight-to-midnight day); paging forward past today snaps back to
     *  live instead of showing an empty "tomorrow". */
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
            dateRangeLabel.text = getString(R.string.graph_live_label)
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
            backToLiveButton.visibility = View.VISIBLE
        }
    }

    /** Finds the nearest plotted entry to a long-press in *pixel* space (not
     *  data-unit space - X is minutes and Y is mg/dL, wildly different
     *  scales, so comparing raw value deltas would favor whichever axis has
     *  the bigger numbers). A generous ~24dp hit radius since a fingertip is
     *  nowhere near pixel-precise. */
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

        clearEventIcons()
        clearAxisTickLabels()

        if (windowed.isEmpty()) {
            val label = if (range == null) {
                windowLabel(selectedWindowMinutes)
            } else {
                dateRangeLabel.text.toString()
            }
            chart.setNoDataText(
                if (range == null) getString(R.string.chart_no_data, label)
                else getString(R.string.graph_no_data_for_range, label)
            )
            chart.clear()
            return
        }

        applyYAxisRange(windowed)

        val anchor = windowStart.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant()
        chartAnchor = anchor

        val dataSets = GapSegmenter.segment(windowed).map { segment ->
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
                // The tapped-point value/time is shown via our own MarkerView
                // (chart.marker below) - MPAndroidChart's default highlight
                // also draws its own vertical+horizontal indicator lines
                // through the tapped point, which duplicated the "now"
                // LimitLine near the right edge. Disabling these leaves
                // exactly one line there.
                setDrawHorizontalHighlightIndicator(false)
                setDrawVerticalHighlightIndicator(false)
            }
        }

        // Tapping a point shows its exact value + timestamp in a callout
        // (GlucoseMarkerView, shared with MainActivity's home chart).
        chart.marker = GlucoseMarkerView(this, anchor, zone)

        // Threshold lines are Y-VALUE thresholds (70/180 mg/dL), so they
        // belong on axisLeft as HORIZONTAL lines.
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
            // Exactly one "now" indicator: a single thin dashed line, colour
            // kept away from the severity ladder and the purple accent/curve
            // colour so it can't be mistaken for either. Historical mode has
            // no "now" line at all - it's viewing a fixed past range, not a
            // live window.
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
            axisMaximum = minutesFromAnchor(anchor, windowEnd)
            if (range == null) {
                setDrawLabels(true)
                granularity = axisGranularityMinutes(selectedWindowMinutes)
                isGranularityEnabled = true
                setLabelCount(axisLabelCount(selectedWindowMinutes), true)
                valueFormatter = HourAxisFormatter(anchor, zone)
            } else {
                // Multi-day/custom-range view: MPAndroidChart's own
                // granularity-based tick generation reads as either sparse or
                // crowded once the window is more than a day or two, so day-
                // boundary ticks (same logic the doctor report's PDF uses) are
                // drawn as overlay labels instead (placeAxisTickLabels below)
                // and the built-in axis text is turned off to avoid a second,
                // disagreeing set of labels.
                setDrawLabels(false)
            }
        }
        // Clear any stale highlight/marker state before swapping data - the
        // chart now draws a variable number of LineDataSets (one per
        // gap-free segment), so a highlight left over from a previous render
        // with a different dataset count can point at a dataSetIndex that no
        // longer exists, crashing MPAndroidChart's own marker-drawing code.
        chart.highlightValues(null)
        chart.data = LineData(dataSets)
        chart.notifyDataSetChanged()
        // A pinch-zoom/pan from a previous interaction leaves the chart's
        // internal viewport matrix scaled/translated - left as-is, every
        // pixel-space computation done after this render (event icons, axis
        // tick overlay labels, long-press hit-testing) would be transformed
        // through that stale matrix instead of the fresh axisMinimum/Maximum
        // just set above, silently misplacing them. Resetting to the default
        // full-range view on every render keeps pixel transforms predictable;
        // the user's zoom/pan within *this* render is still fully available
        // via setPinchZoom/isDragEnabled afterwards.
        chart.fitScreen()
        chart.invalidate()

        // Icon/label placement needs the chart's pixel transform from this
        // layout pass, which isn't ready until after invalidate() actually
        // draws - post() defers until then.
        chart.post {
            placeEventIcons(visibleEvents, anchor)
            if (range != null) placeAxisTickLabels(windowStart, windowEnd, anchor, zone)
        }
    }

    /** A tappable glyph per logged event, positioned above the chart at the
     *  event's x-position - tapping opens its tag/note in an editable bottom
     *  sheet. Plain overlay Views (not chart Entries/markers) since
     *  MPAndroidChart has no built-in tap target that isn't a data point.
     *
     *  Icons whose natural x-position would land within one icon-width of
     *  the "now" line are nudged sideways (away from the chart's right edge,
     *  i.e. left) so the glyph never sits directly on top of that line -
     *  it's still positioned close to its real timestamp, just not exactly
     *  overlapping the one thing on the chart it would otherwise obscure. */
    private fun placeEventIcons(events: List<UserEvent>, anchor: Instant) {
        val transformer = chart.getTransformer(YAxis.AxisDependency.LEFT)
        // chart.top: the aspect-ratio cap (setupAspectRatioCap) can leave
        // chartContainer taller than the capped chart, vertically centering
        // it - viewPortHandler's offsets are relative to the chart's own
        // origin, not chartContainer's, so that gap must be added before
        // using them as chartContainer margins.
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

    /** Day-boundary x-axis labels for historical/multi-day mode, using the
     *  same tick-generation logic (AxisTicks.xAxisTicks) as the doctor
     *  report's PDF chart - same overlay-View technique as placeEventIcons,
     *  since MPAndroidChart's own axis text is turned off for this mode. */
    private fun placeAxisTickLabels(start: Instant, end: Instant, anchor: Instant, zone: ZoneId) {
        val transformer = chart.getTransformer(YAxis.AxisDependency.LEFT)
        val bottomY = chart.top + chart.viewPortHandler.contentBottom().toInt() + dp(2)
        val mutedColor = ContextCompat.getColor(this, R.color.muted)
        val measurePaint = android.graphics.Paint().apply { textSize = 11f * resources.displayMetrics.scaledDensity }

        // AxisTicks' cadence (day-boundary ticks, or 6 hour-labeled ticks for
        // ranges <=2 days) was tuned for the doctor report's wide printed
        // page, not a narrow phone screen - the same 6 "MMM d, h a" labels
        // that fit comfortably on a PDF can overlap each other here. Skip a
        // label if it would render closer than TICK_LABEL_MIN_GAP_PX to the
        // previously placed one, same collision-avoidance approach the PDF's
        // own y-axis labels use.
        // Clamp each label's left edge to stay on-screen BEFORE the collision
        // check (not after) - a tick at the very edge of the plot naturally
        // wants to center past the screen edge, and clamping that only at
        // render time (after collision bookkeeping used the unclamped
        // position) let the actual on-screen label sit further right than
        // the math assumed, overlapping the next surviving label.
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

    // 1hr shows a tick every 15 min (5 labels), 3hr every 30 min (7 labels),
    // 6hr every hour (7 labels) - density scales down as the window widens
    // instead of a single fixed tick count that reads sparse on a short
    // window and crowded on a long one. labelCount is forced (see
    // renderChart) so the density is guaranteed rather than left to
    // MPAndroidChart's own thin-to-fit heuristic.
    private fun axisGranularityMinutes(windowMinutes: Long): Float = when (windowMinutes) {
        WINDOW_1H -> 15f
        WINDOW_3H -> 30f
        else -> 60f
    }

    private fun axisLabelCount(windowMinutes: Long): Int = when (windowMinutes) {
        WINDOW_1H -> 5
        WINDOW_3H -> 7
        else -> 7
    }

    private fun windowLabel(windowMinutes: Long): String = when (windowMinutes) {
        WINDOW_1H -> "hour"
        WINDOW_3H -> "3 hours"
        else -> "6 hours"
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
        private const val CHART_AUTO_REFRESH_MS = 5 * 60 * 1000L
        private const val ASPECT_RATIO = 0.68f
        private const val RANGE_PREFS_NAME = "ahead_chart_range"
        private const val KEY_RANGE_MODE = "range_mode"
        private const val FLING_VELOCITY_THRESHOLD = 800f
        private const val TICK_LABEL_MIN_GAP_PX = 16f

        fun createIntent(context: Context): Intent = Intent(context, GraphActivity::class.java)
    }
}
