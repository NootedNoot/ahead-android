package com.aheadt1d.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aheadt1d.app.alerts.AlertChannels
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.health.HealthConnectManager
import com.aheadt1d.app.health.NightscoutFallbackClient
import com.aheadt1d.app.events.EventLogDialogs
import com.aheadt1d.app.notifications.GlucoseStatusService
import com.aheadt1d.app.setup.SetupPrefs
import com.aheadt1d.app.setup.SetupWizardActivity
import com.aheadt1d.app.state.DebugGlucoseOverride
import com.aheadt1d.app.state.LatestTrend
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.state.RawReading
import com.aheadt1d.app.state.effectiveRatePerMinute
import com.aheadt1d.app.state.isStale
import com.aheadt1d.app.state.staleGuidance
import com.aheadt1d.app.ui.GlucoseSeverity
import com.aheadt1d.app.voice.VoiceAlertsActivity
import com.aheadt1d.app.work.WorkScheduler
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var chart: LineChart
    private lateinit var window1hButton: Button
    private lateinit var window3hButton: Button
    private lateinit var window6hButton: Button

    private lateinit var rangeTightButton: Button
    private lateinit var rangeFullButton: Button
    private lateinit var rangeAutoButton: Button

    private var cachedPoints: List<GlucosePoint> = emptyList()
    private var selectedWindowMinutes = WINDOW_1H

    /** Y-axis display range. Explicit user control rather than intuitive-only
     *  auto-scaling: a clipped chart during a high reading hides exactly the
     *  trend the user most needs to see, so the ceiling must never silently
     *  cut off real data (CGMs report up to ~400 mg/dL). */
    private enum class RangeMode { TIGHT, FULL, AUTO }

    private var rangeMode = RangeMode.FULL

    private val requestHealthConnectPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectManager.ALL_PERMISSIONS)) {
            WorkScheduler.schedulePeriodic(applicationContext)
            refreshChart()
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op either way - the persistent notification just won't show if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First launch (or setup never completed): hand off to the guided
        // wizard and don't build the dashboard this time around.
        if (!SetupPrefs.isComplete(this)) {
            startActivity(SetupWizardActivity.createIntent(this))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        chart = findViewById(R.id.glucoseChart)
        window1hButton = findViewById(R.id.window1hButton)
        window3hButton = findViewById(R.id.window3hButton)
        window6hButton = findViewById(R.id.window6hButton)
        rangeTightButton = findViewById(R.id.rangeTightButton)
        rangeFullButton = findViewById(R.id.rangeFullButton)
        rangeAutoButton = findViewById(R.id.rangeAutoButton)
        loadRangePrefs()
        setupChart()
        window1hButton.setOnClickListener { selectWindow(WINDOW_1H) }
        window3hButton.setOnClickListener { selectWindow(WINDOW_3H) }
        window6hButton.setOnClickListener { selectWindow(WINDOW_6H) }
        rangeTightButton.setOnClickListener { setRangeMode(RangeMode.TIGHT) }
        rangeFullButton.setOnClickListener { setRangeMode(RangeMode.FULL) }
        rangeAutoButton.setOnClickListener { setRangeMode(RangeMode.AUTO) }
        updateWindowButtonStyles()
        updateRangeButtonStyles()

        findViewById<View>(R.id.glucoseTrendCard).setOnClickListener {
            startActivity(GraphActivity.createIntent(this))
        }

        findViewById<View>(R.id.logEventFab).apply {
            setOnClickListener { EventLogDialogs.showPresetPicker(this@MainActivity, lifecycleScope) }
            setOnLongClickListener {
                EventLogDialogs.showCustomNoteDialog(this@MainActivity, lifecycleScope)
                true
            }
        }

        // Jumps straight to the DND-access settings screen - same destination
        // the setup wizard's own DND step uses. Visibility itself is toggled
        // in updateDndRegressionBanner(), called from onResume.
        findViewById<View>(R.id.dndRegressionBanner).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        }

        // No manual "Check now" control by design: the foreground service's own
        // 5-min loop is the single source of fresh data, backed by the exact-alarm
        // + WorkManager watchdogs. If a manual refresh ever felt necessary, that
        // would mean the service isn't staying alive - a bug to fix there, not to
        // paper over with a button. The chart still auto-refreshes reactively via
        // the StateFlow observers below.
        setupVersionText()

        observeLatestTrend()
        observeWorkerRuns()
        autoRefreshChart()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Started unconditionally (not gated on Health Connect permissions) so
        // it can show its own "no data yet" state - GlucoseCheckWorker also
        // starts this on every run, so this covers the case where the app is
        // opened before the periodic worker has ever run.
        GlucoseStatusService.ensureRunning(applicationContext)

        if (!HealthConnectManager.isAvailable(this)) {
            findViewById<TextView>(R.id.statusText).setText(R.string.status_hc_missing)
            return
        }

        lifecycleScope.launch {
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(HealthConnectManager.ALL_PERMISSIONS)) {
                WorkScheduler.schedulePeriodic(applicationContext)
                refreshChart()
            } else {
                requestHealthConnectPermissions.launch(HealthConnectManager.ALL_PERMISSIONS)
            }
        }
    }

    /**
     * Shows the build/version string at the bottom of the dashboard. In DEBUG
     * builds only, long-pressing it offers to re-run the setup wizard (clearing
     * just the wizard-completion state), so the flow can be tested repeatedly
     * without a full uninstall / `pm clear`. The listener is never attached in
     * release builds, so the gesture does nothing for real users.
     */
    private fun setupVersionText() {
        findViewById<TextView>(R.id.voiceAlertsEntry).setOnClickListener {
            startActivity(VoiceAlertsActivity.createIntent(this))
        }
        findViewById<TextView>(R.id.emergencyContactsEntry).setOnClickListener {
            startActivity(com.aheadt1d.app.emergency.EmergencyContactsActivity.createIntent(this))
        }
        findViewById<TextView>(R.id.cgmPathEntry).setOnClickListener {
            showCgmPathDialog()
        }

        val versionText = findViewById<TextView>(R.id.versionText)
        val suffix = if (BuildConfig.DEBUG) " (debug)" else ""
        versionText.text = "v${BuildConfig.VERSION_NAME}$suffix"

        if (!BuildConfig.DEBUG) return

        val debugLabel = findViewById<TextView>(R.id.debugLabel)
        debugLabel.visibility = View.VISIBLE
        debugLabel.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Debug tools")
                .setItems(arrayOf("Tuning parameters", "Debug Menu", "Reset setup wizard")) { _, which ->
                    // Debug source-set classes: reference by name so release
                    // compilation never requires them, and these paths are
                    // unreachable outside BuildConfig.DEBUG.
                    when (which) {
                        0 -> startActivity(Intent().setClassName(packageName, "$packageName.debug.TuningActivity"))
                        1 -> startActivity(Intent().setClassName(packageName, "$packageName.debug.DebugMenuActivity"))
                        else -> {
                            SetupPrefs.resetWizardState(this)
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        }
                    }
                }
                .show()
            true
        }
    }

    /**
     * Lets the CGM sync path (see SetupPrefs doc) be corrected any time after
     * setup, in both debug and release builds - previously the wizard's
     * choice was permanent short of a full "Reset setup wizard," so a wrong
     * pick (or the wizard silently re-running and re-picking a default)
     * quietly widened the staleness-alert threshold with no way to notice.
     */
    private fun showCgmPathDialog() {
        val paths = arrayOf(SetupPrefs.PATH_DEXCOM, SetupPrefs.PATH_JUGGLUCO, SetupPrefs.PATH_UNSURE)
        val labels = arrayOf("Dexcom", "Juggluco", "Not sure")
        val current = paths.indexOf(SetupPrefs.cgmPath(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cgm_path_dialog_title))
            .setMessage(getString(R.string.cgm_path_dialog_message))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                SetupPrefs.setCgmPath(this, paths[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Health Connect's own permission screen is a separate app the user
    // bounces to and back from, not a dialog on top of this Activity - if
    // they grant access there and switch back without this Activity ever
    // being re-created, onCreate's one-time check would never notice.
    // Silent on purpose: if permission is still missing, don't re-launch the
    // request here (onCreate already asked once) - re-prompting on every
    // resume would be obnoxious.
    override fun onResume() {
        super.onResume()
        // Catches a DND-access revocation that happened while the app wasn't
        // in the foreground (system "clean up permissions" prompt, an OEM
        // auto-revoke, the user toggling it off in Settings) - the wizard
        // only ever surfaces this once, during first-run setup.
        updateDndRegressionBanner()
        if (!HealthConnectManager.isAvailable(this)) return
        lifecycleScope.launch {
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(HealthConnectManager.ALL_PERMISSIONS)) {
                WorkScheduler.schedulePeriodic(applicationContext)
                refreshChart()
            }
        }
    }

    private fun updateDndRegressionBanner() {
        findViewById<View>(R.id.dndRegressionBanner).visibility =
            if (AlertChannels.dndAccessRegressed(this)) View.VISIBLE else View.GONE
    }

    // GlucoseCheckWorker runs in this same process and publishes to
    // LatestTrendRepository's StateFlow after every run - collecting it here
    // (scoped to STARTED so it pauses while backgrounded) keeps the screen in
    // sync in real time without polling anything ourselves. A fresh trend
    // value is also a good hint that new Health Connect data just landed, so
    // it doubles as a chart-refresh trigger.
    private fun observeLatestTrend() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LatestTrendRepository.latestTrend.collect { trend -> renderTrendState(trend) }
            }
        }
    }

    // This is the reliable trigger for "Check now" and every periodic run:
    // it fires whenever the Worker successfully reads Health Connect, even if
    // the backend never returns a usable trend (dedup'd it away, was slow,
    // errored, etc). observeLatestTrend() alone would miss those cases.
    private fun observeWorkerRuns() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LatestTrendRepository.lastCheckedAt.collect {
                    refreshChart()
                }
            }
        }
    }

    // CGMs sync into Health Connect roughly every 5 min regardless of the
    // Worker's 15-min cadence, so poll a bit more often while the screen is
    // actually open to keep the chart feeling live.
    private fun autoRefreshChart() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(CHART_AUTO_REFRESH_MS)
                    refreshChart()
                }
            }
        }
    }

    private fun refreshChart() {
        updateDebugOverrideBanner()
        lifecycleScope.launch {
            val canReadHealthConnect = HealthConnectManager.canReadGlucose(applicationContext)
            cachedPoints = if (canReadHealthConnect) {
                HealthConnectManager.readGlucosePoints(applicationContext, WINDOW_6H)
            } else {
                emptyList()
            }

            if (cachedPoints.isEmpty() && !canReadHealthConnect) {
                // Health Connect itself can't be read right now (not
                // installed, or this app's permission was revoked) - same
                // fallback GlucoseCheckRunner uses for the notification/
                // backend pipeline, so this screen's own chart/number don't
                // sit blank while the notification elsewhere on the phone is
                // already showing real data from the same underlying CGM.
                cachedPoints = NightscoutFallbackClient.readGlucosePoints(WINDOW_6H)
                Log.i(TAG, "refreshChart: Health Connect unavailable - using Nightscout fallback (${cachedPoints.size} point(s))")
            }

            Log.d(TAG, "refreshChart: read ${cachedPoints.size} point(s), latest=${cachedPoints.lastOrNull()}")
            syncRawReadingToRepository()
            renderCurrentValue()
            // Re-checked here too (not just from observeLatestTrend's reactive
            // collector) since staleness is a function of the clock, not just
            // of a new trend arriving - without this, a trend that arrived once
            // and then stopped updating would sit there looking falsely current
            // forever, the same bug the persistent notification had before it
            // got its own staleness check.
            renderTrendState(LatestTrendRepository.latestTrend.value)
            renderChart()
        }
    }

    /**
     * Closes a phase gap between this screen and the persistent notification:
     * this poll and GlucoseStatusService's own 5-min check loop both read
     * Health Connect independently, on unsynchronized timers (this one anchored
     * to whenever the screen was opened, the service's to whenever it last
     * started) - so it's routine for this poll to see a fresh CGM sync a few
     * minutes before the service's own loop happens to tick. Previously that
     * meant the number on this screen could be visibly ahead of what the
     * notification showed, by up to a full cycle, purely from the two timers'
     * offset - not from either being broken. Writing straight into
     * LatestTrendRepository (the same store GlucoseCheckRunner writes to) and
     * immediately refreshing the notification means whichever of the app's
     * three Health Connect pollers (this one, the service loop, the Worker
     * watchdog) sees new data first is the one that updates it - the other two
     * just no-op next time since nothing's newer.
     */
    private fun syncRawReadingToRepository() {
        val latest = cachedPoints.lastOrNull() ?: return
        val current = LatestTrendRepository.latestRawReading.value
        if (current != null && latest.time.toEpochMilli() <= current.time) return

        LatestTrendRepository.updateRawReading(
            applicationContext,
            RawReading(
                value = latest.sgv,
                time = latest.time.toEpochMilli(),
                ratePerMinute = HealthConnectManager.calculateRatePerMinute(cachedPoints),
                deltaFromPrevious = HealthConnectManager.calculateDelta(cachedPoints)
            )
        )
        GlucoseStatusService.refreshNotification(applicationContext)
    }

    // The glucose number comes straight from Health Connect, not from waiting
    // on a round trip to the backend - that way it shows up even if the
    // backend is slow, unreachable, or hasn't responded yet. But a value that
    // exists and a value that's actually current aren't the same thing: an
    // hours-old reading is still "the last point in the 6h query window" and
    // would otherwise display with the same confident color/formatting as a
    // fresh one.
    private fun renderCurrentValue() {
        val valueView = findViewById<TextView>(R.id.latestValueText)
        val statusView = findViewById<TextView>(R.id.statusText)
        val latest = cachedPoints.lastOrNull()

        if (latest == null || !isFresh(latest.time)) {
            valueView.text = getString(R.string.no_reading_yet)
            valueView.setTextColor(ContextCompat.getColor(this, R.color.muted))
            statusView.text = if (latest == null) {
                getString(R.string.status_no_data)
            } else {
                // Same cause-aware guidance the notification shows (shared
                // staleGuidance) - the two surfaces must never disagree about
                // whether to blame the sensor or the app's own access.
                "${getString(R.string.status_stale, formatAge(latest.time))} ${staleGuidance(LatestTrendRepository.readBlocked.value)}"
            }
            return
        }

        valueView.text = "${latest.sgv}"
        applySeverityToNumber(valueView, latest.sgv)
        statusView.setText(R.string.status_running)
    }

    /**
     * Colours the big number via the single-source-of-truth GlucoseSeverity
     * ladder. The four lighter buckets colour the number directly; the two
     * darkest (severe-low, critical-high) are too dark to be legible as text on
     * the near-black bg, so they render as a coloured fill behind a light
     * number - which also reads as more severe, by design. The bucket word is
     * set as the contentDescription so the state is never colour-only.
     */
    private fun applySeverityToNumber(valueView: TextView, sgv: Int) {
        val bucket = GlucoseSeverity.bucketFor(sgv)
        valueView.setTextColor(ContextCompat.getColor(this, bucket.numberColorRes))
        valueView.contentDescription = "$sgv mg/dL, ${bucket.label}"
        if (bucket.usesFill) {
            valueView.setBackgroundResource(R.drawable.number_fill_bg)
            valueView.backgroundTintList =
                ContextCompat.getColorStateList(this, bucket.colorRes)
            valueView.setPadding(dpToPx(20), dpToPx(4), dpToPx(20), dpToPx(4))
        } else {
            valueView.background = null
            valueView.setPadding(0, 0, 0, 0)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** Debug builds only: HealthConnectManager.readGlucosePoints silently
     *  serves DebugGlucoseOverride's data instead of real Health Connect
     *  whenever it's set (see the Debug Menu's glucose injection tools) -
     *  every poller (this screen, GlucoseCheckWorker, GlucoseStatusService)
     *  shares that one function, so leaving an override active after testing
     *  makes a real CGM outage indistinguishable from "forgot to clear the
     *  debug override." This banner is the tell. */
    private fun updateDebugOverrideBanner() {
        if (!BuildConfig.DEBUG) return
        findViewById<View>(R.id.debugOverrideBanner).visibility =
            if (DebugGlucoseOverride.isActive) View.VISIBLE else View.GONE
    }

    // Routes through the shared isStale() (state package) - the same rule the
    // notification and setup wizard use, so no surface can drift its boundary.
    private fun isFresh(instant: Instant): Boolean = !isStale(this, instant.toEpochMilli())

    private fun formatAge(instant: Instant): String {
        val minutes = Duration.between(instant, Instant.now()).toMinutes()
        if (minutes < 60) return "$minutes min"
        val hours = minutes / 60
        val remainder = minutes % 60
        return if (remainder == 0L) "${hours}h" else "${hours}h ${remainder}m"
    }

    private fun selectWindow(minutes: Long) {
        selectedWindowMinutes = minutes
        updateWindowButtonStyles()
        // Re-fetches rather than just re-filtering cachedPoints, so switching
        // tabs always reflects the freshest Health Connect data instead of
        // whatever happened to be cached from the last refresh.
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
        // Range is display-only - re-render from cache, no Health Connect
        // re-fetch needed.
        renderChart()
    }

    private fun updateRangeButtonStyles() {
        setButtonActive(rangeTightButton, rangeMode == RangeMode.TIGHT)
        setButtonActive(rangeFullButton, rangeMode == RangeMode.FULL)
        setButtonActive(rangeAutoButton, rangeMode == RangeMode.AUTO)
    }

    private fun applyYAxisRange(windowed: List<GlucosePoint>) {
        val (minY, maxY) = when (rangeMode) {
            RangeMode.TIGHT -> 70f to 180f
            RangeMode.FULL -> 40f to 400f
            RangeMode.AUTO -> {
                // Fit to the visible window's actual data with breathing room.
                // Padded max is NOT capped - if a sensor ever reports above
                // 400, auto-fit must still show it rather than clip.
                val lo = windowed.minOf { it.sgv }.toFloat()
                val hi = windowed.maxOf { it.sgv }.toFloat()
                (lo - AUTO_RANGE_PADDING).coerceAtLeast(0f) to hi + AUTO_RANGE_PADDING
            }
        }
        chart.axisLeft.axisMinimum = minY
        chart.axisLeft.axisMaximum = maxY
    }

    private fun loadRangePrefs() {
        val prefs = getSharedPreferences(RANGE_PREFS_NAME, MODE_PRIVATE)
        // runCatching also covers a persisted mode that no longer exists
        // (e.g. "CUSTOM" from a build that had it) - falls back to FULL.
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
        // Time-axis zoom/pan uses MPAndroidChart's built-in gestures - pinch
        // to zoom, drag to pan. Y stays fixed to the selected range mode so
        // zooming can never hide the 70/180 limit lines' meaning.
        chart.setPinchZoom(true)
        chart.isDoubleTapToZoomEnabled = false
        chart.isDragEnabled = true
        chart.isScaleXEnabled = true
        chart.isScaleYEnabled = false
        chart.axisRight.isEnabled = false

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
            // granularity/axisMinimum/axisMaximum/valueFormatter are all set
            // per-render in renderChart() - they depend on the selected window
            // and the current time.
        }

        chart.axisLeft.apply {
            // axisMinimum/axisMaximum are applied per-render by
            // applyYAxisRange() from the user's selected range mode - no
            // hardcoded ceiling here (the old fixed 300f max clipped high
            // readings; CGMs report up to ~400).
            setDrawGridLines(true)
            gridColor = borderColor
            textColor = mutedColor
            textSize = 10f
            setDrawAxisLine(false)
            addLimitLine(LimitLine(70f).apply {
                lineColor = ContextCompat.getColor(this@MainActivity, R.color.low)
                lineWidth = 1f
                enableDashedLine(10f, 6f, 0f)
            })
            addLimitLine(LimitLine(180f).apply {
                lineColor = ContextCompat.getColor(this@MainActivity, R.color.high)
                lineWidth = 1f
                enableDashedLine(10f, 6f, 0f)
            })
        }
    }

    private fun renderChart() {
        val now = Instant.now()
        val cutoff = now.minus(Duration.ofMinutes(selectedWindowMinutes))
        val windowed = cachedPoints.filter { it.time.isAfter(cutoff) }

        if (windowed.isEmpty()) {
            chart.setNoDataText(getString(R.string.chart_no_data, windowLabel(selectedWindowMinutes)))
            chart.clear()
            return
        }

        applyYAxisRange(windowed)

        // x-axis values are minutes elapsed since `anchor`, not a sequential
        // point index - anchor is pinned to a whole hour so that MPAndroidChart's
        // own "nice interval" tick placement (which always starts at a multiple
        // of the axis granularity from x=0) lands exactly on the hour instead of
        // wherever the first/last data point happens to fall.
        val zone = ZoneId.systemDefault()
        val anchor = cutoff.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant()

        val entries = windowed.map { point -> Entry(minutesFromAnchor(anchor, point.time), point.sgv.toFloat()) }
        val pointColors = windowed.map { colorIntFor(it.sgv) }

        val dataSet = LineDataSet(entries, "Glucose").apply {
            color = ContextCompat.getColor(this@MainActivity, R.color.accent)
            lineWidth = 2f
            setDrawCircleHole(false)
            circleRadius = 3f
            setCircleColors(pointColors)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            highLightColor = ContextCompat.getColor(this@MainActivity, R.color.accent2)
        }

        chart.marker = GlucoseMarkerView(this, anchor, zone)
        chart.xAxis.apply {
            axisMinimum = minutesFromAnchor(anchor, cutoff)
            axisMaximum = minutesFromAnchor(anchor, now)
            granularity = axisGranularityMinutes(selectedWindowMinutes)
            isGranularityEnabled = true
            valueFormatter = HourAxisFormatter(anchor, zone)
        }
        chart.data = LineData(dataSet)
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    private fun minutesFromAnchor(anchor: Instant, instant: Instant): Float =
        Duration.between(anchor, instant).toMillis() / 60_000f

    // 1hr is too short for hourly ticks to mean much (often only 1-2 would fit),
    // so it gets a finer 20-min step; 3hr/6hr use a full hour so labels read as
    // clean "1 PM / 2 PM / 3 PM" marks.
    private fun axisGranularityMinutes(windowMinutes: Long): Float = when (windowMinutes) {
        WINDOW_1H -> 20f
        else -> 60f
    }

    private fun windowLabel(windowMinutes: Long): String = when (windowMinutes) {
        WINDOW_1H -> "hour"
        WINDOW_3H -> "3 hours"
        else -> "6 hours"
    }

    /** Formats an x-axis tick (minutes since `anchor`) as a clean clock time -
     *  just the hour when the tick lands exactly on one (the common case,
     *  guaranteed whenever granularity is a multiple of 60), otherwise falls
     *  back to hour:minute for the finer 1hr-window ticks. */
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

    // Same principle as renderCurrentValue(): a trend that exists isn't
    // necessarily a trend that's current. The backend can go a long time
    // without returning anything new (dedup, downtime, a redeploy resetting
    // its in-memory state) while this stays showing "Stable" from hours ago
    // with nothing to indicate it's not live anymore.
    private fun renderTrendState(trend: LatestTrend?) {
        val severityView = findViewById<TextView>(R.id.latestSeverityText)
        val projectionContainer = findViewById<View>(R.id.projectionContainer)
        val projectionView = findViewById<TextView>(R.id.projectionText)
        // Freshness is checked against the raw Health Connect reading, not
        // trend.date. The backend dedups check-trend calls server-side and can
        // stop advancing trend.date for 30+ minutes while fresh HC readings
        // keep arriving — using trend.date here would cause the trend line to
        // show "No recent trend data" even when glucose data is perfectly live.
        val rawReading = LatestTrendRepository.latestRawReading.value
        val rawIsFresh = rawReading != null && isFresh(Instant.ofEpochMilli(rawReading.time))
        if (trend == null || rawReading == null || !rawIsFresh) {
            severityView.setText(R.string.trend_unavailable)
            projectionContainer.visibility = View.GONE
            return
        }
        // Rate comes from the shared effectiveRatePerMinute(), NOT trend.rate
        // directly - the persistent notification renders from the same
        // function, so both surfaces always show the identical rate for a
        // given check cycle.
        val rate = effectiveRatePerMinute(rawReading, trend)
        severityView.text = describe(trend, rate)

        // Projections are a straight-line extrapolation from the SAME effective
        // rate shown in the line above (value + rate * minutes), so the number,
        // the rate, and the projection can never disagree. Hidden when no rate
        // is computable - a lone reading with nothing to diff against.
        if (rate == null) {
            projectionContainer.visibility = View.GONE
            return
        }
        val projected15 = (rawReading.value + rate * PROJECTION_15_MIN).roundToInt()
        val projected30 = (rawReading.value + rate * PROJECTION_30_MIN).roundToInt()
        projectionView.text = "$projected15 in 15m · $projected30 in 30m"
        projectionContainer.visibility = View.VISIBLE
    }

    // Chart point colours route through the same severity ladder as the number,
    // so a dot and the big number can never disagree about what a value means.
    private fun colorIntFor(sgv: Int): Int =
        ContextCompat.getColor(this, GlucoseSeverity.bucketFor(sgv).colorRes)

    private fun describe(trend: LatestTrend, rate: Double?): String {
        val severityLabel = when (trend.severity) {
            "red" -> "Red alert"
            "yellow" -> "Yellow"
            // "Stable" describes the number, not the severity tier - severity
            // can be 'none' while the rate is still moving fast enough that
            // calling it "Stable" would misrepresent what's happening.
            else -> trendLabelFor(rate)
        }
        val rateText = rate?.let {
            val sign = if (it > 0) "+" else ""
            "$sign${"%.1f".format(it)} mg/dL/min"
        } ?: "rate unknown"
        return "$severityLabel · $rateText"
    }

    private fun trendLabelFor(rate: Double?): String = when {
        rate == null -> "Stable"
        abs(rate) < STABLE_RATE_THRESHOLD -> "Stable"
        rate <= -FAST_RATE_THRESHOLD -> "Dropping Fast"
        rate >= FAST_RATE_THRESHOLD -> "Rising Fast"
        rate < 0 -> "Falling"
        else -> "Rising"
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val WINDOW_1H = 60L
        private const val WINDOW_3H = 180L
        private const val WINDOW_6H = 360L
        private const val CHART_AUTO_REFRESH_MS = 5 * 60 * 1000L
        private const val AUTO_RANGE_PADDING = 20f
        private const val RANGE_PREFS_NAME = "ahead_chart_range"
        private const val KEY_RANGE_MODE = "range_mode"
        // Below this, movement is noise, not a real trend - matches the rate
        // magnitude "Stable" is supposed to communicate.
        private const val STABLE_RATE_THRESHOLD = 0.5
        // At/above this, "Falling"/"Rising" undersells it - deliberately below
        // trend-detector.js's own YELLOW_RATE_FALLING/RISING (1.5/2.5) escalation
        // points, so this label can flag a fast move even while severity is
        // still 'none' from a stale/out-of-tolerance backend trend.
        private const val FAST_RATE_THRESHOLD = 2.0
        // Projection horizons shown under the current value. 15/30 match the
        // backend's PROJECTION_MINUTES / EXTENDED_PROJECTION_MINUTES defaults.
        private const val PROJECTION_15_MIN = 15
        private const val PROJECTION_30_MIN = 30
    }
}
