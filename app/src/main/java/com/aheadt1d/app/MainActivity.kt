package com.aheadt1d.app

import android.animation.ObjectAnimator
import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aheadt1d.app.alerts.AlertChannels
import com.aheadt1d.app.alerts.AlertSilenceManager
import com.aheadt1d.app.auth.AuthPrefs
import com.aheadt1d.app.auth.LoginActivity
import org.aheadt1d.ratemath.RateMath
import org.aheadt1d.ratemath.RatePoint
import org.aheadt1d.ratemath.TrajectoryKind
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.health.HealthConnectManager
import com.aheadt1d.app.notifications.GlucoseStatusService
import com.aheadt1d.app.setup.SetupPrefs
import com.aheadt1d.app.setup.SetupWizardActivity
import com.aheadt1d.app.state.DebugGlucoseOverride
import com.aheadt1d.app.state.LatestTrend
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.state.RawReading
import com.aheadt1d.app.state.TREND_MATCH_TOLERANCE_MS
import com.aheadt1d.app.state.effectiveRatePerMinute
import com.aheadt1d.app.state.isStale
import com.aheadt1d.app.state.staleGuidance
import kotlin.math.abs
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
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var chart: LineChart
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var liveDotCore: View
    private lateinit var liveDotRing: View
    private var liveDotAnimators: List<ObjectAnimator> = emptyList()

    private var cachedPoints: List<GlucosePoint> = emptyList()

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

        // Checked BEFORE the wizard, deliberately: AuthPrefs.isSetUp() is
        // "do we have a device key" (permanent, never expires - see that
        // object's doc), not "is the app fully configured." An existing
        // user updating onto accounts already has SetupPrefs.isComplete =
        // true and gets sent straight through Login back to the dashboard,
        // no wizard replay; a fresh install goes Login -> Wizard ->
        // Dashboard. Either way this ordering means the wizard never has to
        // know or care about login state.
        if (!AuthPrefs.isSetUp(this)) {
            startActivity(LoginActivity.createIntent(this))
            finish()
            return
        }

        // First launch (or setup never completed): hand off to the guided
        // wizard and don't build the dashboard this time around.
        if (!SetupPrefs.isComplete(this)) {
            startActivity(SetupWizardActivity.createIntent(this))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        chart = findViewById(R.id.glucoseChart)
        drawerLayout = findViewById(R.id.drawerLayout)
        liveDotCore = findViewById(R.id.liveDotCore)
        liveDotRing = findViewById(R.id.liveDotRing)
        setupChart()
        setupDrawer()

        findViewById<View>(R.id.glucoseTrendCard).setOnClickListener {
            startActivity(GraphActivity.createIntent(this))
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
     * Wires the hamburger button + every drawer destination, and shows the
     * three debug-only items only in a debug build (mirrors the old hidden
     * long-press "Debug tools" dialog, which this replaces entirely - see
     * git history for that prior mechanism). One consolidated place instead
     * of a scattered footer row + hidden long-press.
     */
    private fun setupDrawer() {
        findViewById<View>(R.id.hamburgerButton).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<View>(R.id.drawerDashboardItem).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.drawerGraphItem).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(GraphActivity.createIntent(this))
        }
        findViewById<View>(R.id.drawerNotesItem).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(com.aheadt1d.app.events.EventHistoryActivity.createIntent(this))
        }
        findViewById<View>(R.id.drawerReportItem).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(com.aheadt1d.app.report.ReportExportActivity.createIntent(this))
        }
        findViewById<View>(R.id.drawerHealthItem).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(com.aheadt1d.app.health.HealthActivity.createIntent(this))
        }
        findViewById<View>(R.id.drawerSilenceItem).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showSilenceDialog()
        }
        findViewById<View>(R.id.drawerVoiceItem).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(VoiceAlertsActivity.createIntent(this))
        }
        findViewById<View>(R.id.drawerCgmSyncItem).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showCgmPathDialog()
        }
        findViewById<View>(R.id.drawerUploadItem).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(com.aheadt1d.app.upload.UploadSettingsActivity.createIntent(this))
        }
        findViewById<View>(R.id.drawerArchiveItem).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(com.aheadt1d.app.data.ArchiveBrowserActivity.createIntent(this))
        }
        findViewById<View>(R.id.drawerAccountItem).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(com.aheadt1d.app.account.AccountSettingsActivity.createIntent(this))
        }
        findViewById<TextView>(R.id.drawerUserLabel).text = AuthPrefs.displayLabel(this) ?: ""
        updateDrawerSilenceLabel()

        // Both conditions required, deliberately - see AuthPrefs.isOwner's
        // doc: BuildConfig.DEBUG alone would show developer tooling to any
        // caregiver/family account that happens to be logged into a debug
        // build Ryan handed them for testing.
        if (BuildConfig.DEBUG && AuthPrefs.isOwner(this)) {
            findViewById<View>(R.id.drawerDebugSectionLabel).visibility = View.VISIBLE
            findViewById<View>(R.id.drawerDebugMenuItem).visibility = View.VISIBLE
            findViewById<View>(R.id.drawerTuningItem).visibility = View.VISIBLE
            findViewById<View>(R.id.drawerResetWizardItem).visibility = View.VISIBLE

            // Debug source-set classes: reference by name so release
            // compilation never requires them, and these paths are
            // unreachable outside BuildConfig.DEBUG anyway.
            findViewById<View>(R.id.drawerDebugMenuItem).setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                startActivity(Intent().setClassName(packageName, "$packageName.debug.DebugMenuActivity"))
            }
            findViewById<View>(R.id.drawerTuningItem).setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                startActivity(Intent().setClassName(packageName, "$packageName.debug.TuningActivity"))
            }
            findViewById<View>(R.id.drawerResetWizardItem).setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                SetupPrefs.resetWizardState(this)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }

        // Predictive-back-friendly: close the drawer first if it's open,
        // only fall through to the normal back behavior once it's closed.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showSilenceDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_silence_alerts, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("Close", null)
            .create()

        val isSilenced = AlertSilenceManager.isSilenced(this)
        val remaining = AlertSilenceManager.getRemainingMinutes(this)
        val titleView = view.findViewById<TextView>(R.id.silenceDialogTitle)
        val statusView = view.findViewById<TextView>(R.id.silenceDialogStatus)

        if (isSilenced) {
            titleView.text = "🔕 Alerts Silenced (${remaining}m left)"
            statusView.text = "Status: 🔕 SILENCED (${remaining}m remaining)"
            statusView.setTextColor(ContextCompat.getColor(this, R.color.low))
        } else {
            titleView.text = "🔕 Silence All Alerts"
            statusView.text = "Status: Alerts Active (Normal)"
            statusView.setTextColor(ContextCompat.getColor(this, R.color.ok))
        }

        view.findViewById<Button>(R.id.btnSilence10).setOnClickListener {
            AlertSilenceManager.silence(this, 10)
            updateDrawerSilenceLabel()
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnSilence15).setOnClickListener {
            AlertSilenceManager.silence(this, 15)
            updateDrawerSilenceLabel()
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnSilence30).setOnClickListener {
            AlertSilenceManager.silence(this, 30)
            updateDrawerSilenceLabel()
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnSilence60).setOnClickListener {
            AlertSilenceManager.silence(this, 60)
            updateDrawerSilenceLabel()
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnCancelSilence).setOnClickListener {
            AlertSilenceManager.cancelSilence(this)
            updateDrawerSilenceLabel()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateDrawerSilenceLabel() {
        val labelView = findViewById<TextView>(R.id.drawerSilenceLabel) ?: return
        if (AlertSilenceManager.isSilenced(this)) {
            val rem = AlertSilenceManager.getRemainingMinutes(this)
            labelView.text = "🔕 Silenced (${rem}m left)"
            labelView.setTextColor(ContextCompat.getColor(this, R.color.low))
        } else {
            labelView.text = "🔕 Silence Alerts"
            labelView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
    }

    /**
     * Shows the build/version string at the bottom of the dashboard.
     */
    private fun setupVersionText() {
        val versionText = findViewById<TextView>(R.id.versionText)
        val suffix = if (BuildConfig.DEBUG) " (debug)" else ""
        versionText.text = "v${BuildConfig.VERSION_NAME}$suffix"
    }

    /**
     * Lets the CGM sync path (see SetupPrefs doc) be corrected any time after
     * setup, in both debug and release builds - previously the wizard's
     * choice was permanent short of a full "Reset setup wizard," so a wrong
     * pick (or the wizard silently re-running and re-picking a default)
     * quietly widened the staleness-alert threshold with no way to notice.
     *
     * setTitle() carries both the question and the explanatory copy (rather
     * than a separate setMessage()) - real bug, found 2026-08-03 while
     * actually using this dialog for the first time on-device: AlertDialog
     * silently drops setSingleChoiceItems()'s list entirely when setMessage()
     * is also set, so this dialog had never been selectable, for any path,
     * since it was written - it just showed the message and a Cancel button.
     */
    private fun showCgmPathDialog() {
        val paths = arrayOf(SetupPrefs.PATH_DEXCOM, SetupPrefs.PATH_JUGGLUCO, SetupPrefs.PATH_AHEADBLE, SetupPrefs.PATH_UNSURE)
        val labels = arrayOf("Dexcom", "Juggluco", "AheadBLE (direct BLE)", "Not sure")
        val current = paths.indexOf(SetupPrefs.cgmPath(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.cgm_path_dialog_title)}\n\n${getString(R.string.cgm_path_dialog_message)}")
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
        updateDrawerSilenceLabel()
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
            // 2026-08-01: no longer falls back to NightscoutFallbackClient
            // when Health Connect can't be read - that fallback read from the
            // same web endpoint ahead-dashboard's viewer reads from, with no
            // staleness check before it fed straight into
            // syncRawReadingToRepository() below (which this screen shares
            // with GlucoseCheckRunner's own alert-driving repository write,
            // unconditionally, before the isFresh() gate that only governs
            // what THIS screen renders). A stale substitute here could have
            // silently reached the same severity classification a live
            // reading would. Empty here now correctly means "nothing to
            // show" and lets the existing signal-lost/stale state surface.
            cachedPoints = if (canReadHealthConnect) {
                HealthConnectManager.readGlucosePoints(applicationContext, WINDOW_6H)
            } else {
                emptyList()
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
            refreshPassiveContext()
        }
    }

    /**
     * Surfaces PassiveContextEngine's insight (dawn surge, exercise-drop,
     * stubborn-high/sticky-low, curvature) below the current-glucose card -
     * see contextCard's layout comment for why it's wrap_content/GONE rather
     * than taking weighted space. Deliberately built here, not in
     * GlucoseStatusService's alert-critical render loop - this is a new,
     * purely informational surface, and the engine itself never influences
     * severity/AlertCoordinator either way. Built from the SAME display
     * state the notification would show (toDisplayState, extracted from
     * GlucoseStatusService for exactly this reuse) so this card and the
     * notification can never quietly disagree about what "now" looks like.
     */
    private fun refreshPassiveContext() {
        val cardView = findViewById<View>(R.id.contextCard)
        val insightView = findViewById<TextView>(R.id.contextInsightText)
        val tipView = findViewById<TextView>(R.id.contextTipText)

        val raw = LatestTrendRepository.latestRawReading.value
        val trend = LatestTrendRepository.latestTrend.value
        val blocked = LatestTrendRepository.readBlocked.value
        val state = com.aheadt1d.app.notifications.toDisplayState(applicationContext, raw, trend, blocked)

        val reading = state as? com.aheadt1d.app.notifications.GlucoseDisplayState.Reading
        val summary = reading?.let {
            com.aheadt1d.app.health.PassiveContextEngine.evaluateContext(applicationContext, it, cachedPoints)
        }

        val insight = summary?.primaryInsight
        if (insight == null) {
            cardView.visibility = View.GONE
            return
        }
        cardView.visibility = View.VISIBLE
        insightView.text = insight
        val tip = summary.actionableTip
        tipView.text = tip ?: ""
        tipView.visibility = if (tip.isNullOrBlank()) View.GONE else View.VISIBLE
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
        val fresh = latest != null && isFresh(latest.time)

        updateLiveIndicator(fresh, latest?.sgv)

        if (!fresh) {
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

        valueView.text = "${latest!!.sgv}"
        applySeverityToNumber(valueView, latest.sgv)
        statusView.setText(R.string.status_running)
    }

    /**
     * Colors + (de)animates the live dot next to the "Ahead" wordmark - green/
     * amber/red to match whatever severity bucket the current reading is in
     * (same ladder the big number uses), with a slow pulse while that reading
     * is fresh. Greys out flat, no pulse, the moment the connection goes
     * stale - a live-looking indicator next to a dead connection would be
     * actively misleading, not just cosmetic. Pulse cadence is deliberately
     * gentle/slow (2.4s) - an earlier, faster version read as an alarm rather
     * than a calm "yes, this is live" signal.
     */
    private fun updateLiveIndicator(fresh: Boolean, sgv: Int?) {
        val colorRes = if (fresh && sgv != null) GlucoseSeverity.bucketFor(sgv).colorRes else R.color.muted
        val color = ContextCompat.getColor(this, colorRes)
        liveDotCore.backgroundTintList = ColorStateList.valueOf(color)
        liveDotRing.backgroundTintList = ColorStateList.valueOf(color)

        liveDotAnimators.forEach { it.cancel() }
        if (!fresh) {
            liveDotRing.alpha = 0f
            liveDotAnimators = emptyList()
            return
        }

        val scaleX = ObjectAnimator.ofFloat(liveDotRing, View.SCALE_X, 0.6f, 1.7f)
        val scaleY = ObjectAnimator.ofFloat(liveDotRing, View.SCALE_Y, 0.6f, 1.7f)
        val alphaAnim = ObjectAnimator.ofFloat(liveDotRing, View.ALPHA, 0.7f, 0f)
        liveDotAnimators = listOf(scaleX, scaleY, alphaAnim).onEach {
            it.duration = LIVE_PULSE_DURATION_MS
            it.repeatCount = ObjectAnimator.INFINITE
            it.interpolator = DecelerateInterpolator()
            it.start()
        }
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
        val active = DebugGlucoseOverride.isActive
        findViewById<View>(R.id.debugOverrideBanner)?.visibility =
            if (active) View.VISIBLE else View.GONE
        val label = findViewById<TextView>(R.id.currentGlucoseLabel) ?: return
        if (active) {
            label.text = "🚨 * INJECTED TEST DATA * 🚨"
            label.setTextColor(ContextCompat.getColor(this, R.color.low))
        } else {
            label.setText(R.string.current_glucose_label)
            label.setTextColor(ContextCompat.getColor(this, R.color.muted))
        }
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

    private fun setupChart() {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.setDrawGridBackground(false)
        // Time-axis zoom/pan uses MPAndroidChart's built-in gestures - pinch
        // to zoom, drag to pan. Y stays fixed (full 40-400 range, matching
        // the old default "Full" mode - see activity_main.xml's doc on why
        // this card no longer has its own range/window controls).
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
            // per-render in renderChart() - they depend on the current time.
        }

        chart.axisLeft.apply {
            axisMinimum = 40f
            axisMaximum = 400f
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
        val cutoff = now.minus(Duration.ofMinutes(WINDOW_1H))
        val windowed = cachedPoints.filter { it.time.isAfter(cutoff) }

        if (windowed.isEmpty()) {
            chart.setNoDataText(getString(R.string.chart_no_data, "hour"))
            chart.clear()
            return
        }

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

        val ghostEntries = buildGhostLineEntries(windowed, anchor)
        val dataSets = mutableListOf<ILineDataSet>(dataSet)
        var axisMax = minutesFromAnchor(anchor, now)
        // removeAllLimitLines first - renderChart re-runs on every 5-min auto
        // refresh, and this is the "live" marker's only home (the 70/180
        // limit lines live on axisLeft, set once in setupChart, untouched
        // here) - without clearing, each refresh would stack another line.
        chart.xAxis.removeAllLimitLines()
        if (ghostEntries.isNotEmpty()) {
            dataSets.add(ghostLineDataSet(ghostEntries))
            axisMax = ghostEntries.last().x
            // Marks exactly where real data ends and the dashed projection
            // begins (2026-08-04, reported live: without this the ghost line
            // just trails off past the last real point and reads as "the
            // chart lost data" rather than "this part is a projection").
            chart.xAxis.addLimitLine(liveMarkerLine(ghostEntries.first().x))
        }

        chart.marker = GlucoseMarkerView(this, anchor, zone)
        chart.xAxis.apply {
            axisMinimum = minutesFromAnchor(anchor, cutoff)
            axisMaximum = axisMax
            granularity = 20f
            isGranularityEnabled = true
            valueFormatter = HourAxisFormatter(anchor, zone)
        }
        chart.data = LineData(dataSets)
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    /**
     * The "ghost line" (Gemini design suggestion, built 2026-08-03): a faded
     * dashed extension past the last real point showing where RateMath (see
     * ahead-rate-math, shared with ahead-lite-android) - a Kotlin mirror of
     * trend-detector.js's own decay-aware projection - thinks glucose is
     * actually headed, easing the rate toward zero once the recent trend
     * confirms it's decelerating rather than assuming the current rate holds
     * flat forever. The point isn't just visual flair: it's showing the user
     * the SAME reasoning the backend's RED-gating already uses internally,
     * so a fast-but-slowing rise visibly bends over instead of just looking
     * like an ordinary flat projection would - answering "why isn't this
     * alarming yet" at a glance.
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
        // Same accent as the app's own emphasis color, but faded (40% alpha)
        // and dashed - reads as "not real data" at a glance without needing
        // a legend.
        color = ColorUtils.setAlphaComponent(ContextCompat.getColor(this@MainActivity, R.color.accent2), 110)
        lineWidth = 2f
        enableDashedLine(12f, 8f, 0f)
        setDrawCircles(false)
        setDrawValues(false)
        mode = LineDataSet.Mode.LINEAR
    }

    /** Vertical marker at the last real data point - see renderChart's doc
     *  on why it exists. Muted/dotted so it reads as a boundary annotation,
     *  not another data series; label sits at the top so it doesn't collide
     *  with the glucose line itself. */
    private fun liveMarkerLine(xValue: Float): LimitLine = LimitLine(xValue, "Live").apply {
        lineColor = ContextCompat.getColor(this@MainActivity, R.color.muted)
        lineWidth = 1f
        enableDashedLine(4f, 4f, 0f)
        textColor = ContextCompat.getColor(this@MainActivity, R.color.muted)
        textSize = 10f
        labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
    }

    private fun minutesFromAnchor(anchor: Instant, instant: Instant): Float =
        Duration.between(anchor, instant).toMillis() / 60_000f

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
    /**
     * 2026-08-28: rewritten to build the exact same GlucoseDisplayState
     * refreshPassiveContext() already builds (via the shared toDisplayState),
     * instead of independently re-deriving a rate (`rawReading.ratePerMinute
     * ?: trend?.rate`, with no freshness gate on the trend fallback - unlike
     * effectiveRatePerMinute's gated version) and calling
     * SeverityEngine.classify() a second time. Found during a fragmentation
     * audit: this was the only other live call site for SeverityEngine.classify
     * in the app besides GlucoseDisplayState.toDisplayState (the one that
     * actually drives AlertCoordinator), with slightly different inputs - the
     * on-screen severity/projection and the alert-firing severity/projection
     * could theoretically have disagreed. They can't now: same function, same
     * inputs, same answer, computed once. The projection text is now the
     * same decay-aware projection the alert pipeline uses (was previously a
     * plain flat extrapolation here) - a real accuracy improvement, not just
     * a dedup.
     */
    private fun renderTrendState(trend: LatestTrend?) {
        val severityView = findViewById<TextView>(R.id.latestSeverityText)
        val projectionContainer = findViewById<View>(R.id.projectionContainer)
        val projectionView = findViewById<TextView>(R.id.projectionText)

        val rawReading = LatestTrendRepository.latestRawReading.value
        val blocked = LatestTrendRepository.readBlocked.value
        val state = com.aheadt1d.app.notifications.toDisplayState(applicationContext, rawReading, trend, blocked)
        val reading = state as? com.aheadt1d.app.notifications.GlucoseDisplayState.Reading
        if (reading == null) {
            severityView.setText(R.string.trend_unavailable)
            projectionContainer.visibility = View.GONE
            return
        }
        val rate = reading.ratePerMinute
        severityView.text = describe(reading.severity, rate)

        if (rate == null || reading.projected == null) {
            projectionContainer.visibility = View.GONE
            return
        }
        projectionView.text = "${reading.projected} in ${PROJECTION_15_MIN}m" +
            (reading.projectedExtended?.let { " · $it in ${PROJECTION_30_MIN}m" } ?: "")
        projectionContainer.visibility = View.VISIBLE
    }

    // Chart point colours route through the same severity ladder as the number,
    // so a dot and the big number can never disagree about what a value means.
    private fun colorIntFor(sgv: Int): Int =
        ContextCompat.getColor(this, GlucoseSeverity.bucketFor(sgv).colorRes)

    private fun describe(severity: String?, rate: Double?): String {
        val severityLabel = when (severity) {
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
        private const val WINDOW_6H = 360L
        // 2026-08-04: was 5 min, the same cadence as the CGM's own HC sync
        // interval - with no alignment between the two, opening the app
        // could sit on an already-stale reading for up to a full 5 min
        // before this timer ever re-read Health Connect, which is exactly
        // what reads as "stale/not caught up" when actually watching a live
        // BG move. Only runs while the screen is open (repeatOnLifecycle
        // STARTED pauses it in the background) and Health Connect reads are
        // a cheap local DB query, so there's no real cost to polling much
        // more often here - this is purely about feeling live while looking
        // at it, not about the actual alert pipeline's cadence.
        private const val CHART_AUTO_REFRESH_MS = 20 * 1000L
        // Ghost decay line (see buildGhostLineEntries) - 3 rate samples is
        // the minimum assessRateTrajectory needs to confirm 'decelerating'
        // rather than defaulting to a flat projection; 20 min matches the
        // backend's own extended-projection horizon.
        private const val GHOST_RATE_SAMPLES = 3
        private const val GHOST_PROJECTION_MINUTES = 20
        // Slow, calm cadence for the live-dot pulse - see updateLiveIndicator's
        // doc for why this was tuned down from an earlier, more urgent-feeling
        // version.
        private const val LIVE_PULSE_DURATION_MS = 2400L
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
