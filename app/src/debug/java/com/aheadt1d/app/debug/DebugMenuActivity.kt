package com.aheadt1d.app.debug

import android.app.NotificationManager
import android.os.Bundle
import android.os.PowerManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import com.aheadt1d.app.GraphActivity
import com.aheadt1d.app.R
import com.aheadt1d.app.alerts.AlertNotifier
import com.aheadt1d.app.alerts.DebugAlertPrefs
import com.aheadt1d.app.alerts.RedAlertActivity
import com.aheadt1d.app.health.HealthConnectManager
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import com.aheadt1d.app.state.DebugGlucoseOverride
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.voice.VoiceAlertPrefs
import com.aheadt1d.app.work.WorkScheduler
import androidx.core.content.edit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import android.content.Intent

/**
 * Debug-only hub for the manual testing tools described in the debug-menu
 * task: glucose injection (manual/preset/random), notification force-firing,
 * a shortcut into the real chart for screenshot comparisons, and a read-only
 * system-state panel. Reached from MainActivity's version-text long-press
 * (debug builds only), launched via the same setClassName pattern used for
 * TuningActivity so release builds never reference this class.
 *
 * Glucose injection routes through DebugGlucoseOverride (swaps the chart's
 * data source in-memory, never touches the real Health Connect store) and
 * DebugInjection (the same repo->AlertCoordinator path DebugTrendInjector
 * uses for adb testing) - one shared mechanism, two entry points.
 */
class DebugMenuActivity : AppCompatActivity() {

    private var scenarioJob: Job? = null

    private lateinit var manualValueInput: EditText
    private lateinit var manualRateInput: EditText
    private lateinit var manualAgeInput: EditText
    private lateinit var scenarioSpinner: Spinner
    private lateinit var speed10x: RadioButton
    private lateinit var scenarioProgressText: TextView
    private lateinit var randomCountInput: EditText
    private lateinit var autoBackgroundSwitch: Switch
    private lateinit var voiceMasterSwitch: Switch
    private lateinit var disableFullScreenSwitch: Switch
    private lateinit var batteryStatusText: TextView
    private lateinit var hcPermsStatusText: TextView
    private lateinit var dndStatusText: TextView
    private lateinit var injectionStatusText: TextView
    private lateinit var debugEventTagSpinner: Spinner
    private lateinit var debugEventNoteInput: EditText
    private lateinit var debugEventHoursAgoInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_menu)

        manualValueInput = findViewById(R.id.manualValueInput)
        manualRateInput = findViewById(R.id.manualRateInput)
        manualAgeInput = findViewById(R.id.manualAgeInput)
        scenarioSpinner = findViewById(R.id.scenarioSpinner)
        speed10x = findViewById(R.id.speed10x)
        scenarioProgressText = findViewById(R.id.scenarioProgressText)
        randomCountInput = findViewById(R.id.randomCountInput)
        autoBackgroundSwitch = findViewById(R.id.autoBackgroundSwitch)
        voiceMasterSwitch = findViewById(R.id.voiceMasterSwitch)
        disableFullScreenSwitch = findViewById(R.id.disableFullScreenSwitch)
        batteryStatusText = findViewById(R.id.batteryStatusText)
        hcPermsStatusText = findViewById(R.id.hcPermsStatusText)
        dndStatusText = findViewById(R.id.dndStatusText)
        injectionStatusText = findViewById(R.id.injectionStatusText)
        debugEventTagSpinner = findViewById(R.id.debugEventTagSpinner)
        debugEventNoteInput = findViewById(R.id.debugEventNoteInput)
        debugEventHoursAgoInput = findViewById(R.id.debugEventHoursAgoInput)

        scenarioSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            DebugScenario.values().map { it.label }
        )
        debugEventTagSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            com.aheadt1d.app.events.EventTag.entries.map { "${it.glyph} ${it.label}" }
        )

        setupResetAll()
        setupGlucoseInjection()
        setupNotificationTesting()
        setupChartTesting()
        setupSystemState()
        setupNotesHistoryTest()
        setupPlateauTest()

        findViewById<Button>(R.id.closeButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemState()
    }

    // ===================== Reset everything =====================

    /**
     * Broader than clearInjectionButton below: that one only clears the
     * chart's DebugGlucoseOverride. A test session can also leave the alert
     * and plateau coordinators' cooldown/latch state (last severity, red
     * peak tracking, signal-lost-fired, plateau tier) sitting on whatever a
     * forced injection last set, which would otherwise quietly skew how the
     * NEXT real reading gets evaluated - e.g. a real fresh red reading not
     * re-alerting because the coordinator still thinks it already fired for
     * "this episode" from a test injection. This wipes all of it, including
     * the last-known reading/trend itself (so nothing fake lingers as "the
     * current value" even from before the next check runs), then forces one
     * real Health Connect check immediately so the display doesn't just sit
     * on "no data" until the next natural cycle.
     */
    private fun setupResetAll() {
        findViewById<Button>(R.id.resetAllTestStateButton).setOnClickListener {
            stopScenario(null)
            DebugGlucoseOverride.clear()

            AlertNotifier.cancelAlerts(this)
            AlertNotifier.cancelPlateau(this)
            AlertNotifier.cancelCorrection(this)

            getSharedPreferences("ahead_alert_state", MODE_PRIVATE).edit { clear() }
            getSharedPreferences("ahead_plateau_state", MODE_PRIVATE).edit { clear() }

            LatestTrendRepository.clear(this)

            refreshSystemState()
            scenarioProgressText.text = "Test state cleared - alert/plateau history reset. Running a real check now..."
            WorkScheduler.runOnce(applicationContext)
        }
    }

    // ===================== Glucose injection =====================

    private fun setupGlucoseInjection() {
        findViewById<Button>(R.id.injectManualButton).setOnClickListener {
            injectManual(ageMinOverride = null)
        }
        findViewById<Button>(R.id.forceStaleButton).setOnClickListener {
            injectManual(ageMinOverride = staleThresholdMinutesSafe() + 5)
        }
        findViewById<Button>(R.id.playScenarioButton).setOnClickListener { playScenario() }
        findViewById<Button>(R.id.stopScenarioButton).setOnClickListener { stopScenario("Playback stopped") }
        findViewById<Button>(R.id.injectRandomButton).setOnClickListener { injectRandomPoints() }
        findViewById<Button>(R.id.clearInjectionButton).setOnClickListener {
            stopScenario(null)
            DebugGlucoseOverride.clear()
            refreshSystemState()
            scenarioProgressText.text = "Injected data cleared - chart now reads real Health Connect."
        }
        findViewById<Button>(R.id.injectReportTestDataButton).setOnClickListener {
            stopScenario(null)
            val points = twoWeekReportTestPoints()
            DebugGlucoseOverride.setPoints(points)
            refreshSystemState()
            scenarioProgressText.text =
                "Injected ${points.size} points across 14 days (6-day gap in the middle). Open Doctor Report and generate for the last 14 days to test."
        }
        findViewById<Button>(R.id.openDoctorReportButton).setOnClickListener {
            startActivity(com.aheadt1d.app.report.ReportExportActivity.createIntent(this))
        }
    }

    // ===================== Notes history stress test =====================

    /** Logs a real UserEvent backdated by N hours - for exercising the notes
     *  history screen's edit/delete on an entry old enough that GraphActivity's
     *  own 6h chart window would never have fetched it, let alone rendered a
     *  tappable icon for it. */
    private fun setupNotesHistoryTest() {
        findViewById<Button>(R.id.injectBackdatedEventButton).setOnClickListener {
            val tag = com.aheadt1d.app.events.EventTag.entries[debugEventTagSpinner.selectedItemPosition]
            val note = debugEventNoteInput.text.toString()
            val hoursAgo = debugEventHoursAgoInput.text.toString().toLongOrNull() ?: 10L
            val timestamp = System.currentTimeMillis() - hoursAgo * 3600_000L
            lifecycleScope.launch {
                com.aheadt1d.app.events.UserEventRepository.log(this@DebugMenuActivity, tag, note, timestamp)
                scenarioProgressText.text = "Logged '${tag.label}' backdated ${hoursAgo}h - open Notes History to confirm it's editable."
            }
        }
        findViewById<Button>(R.id.openNotesHistoryButton).setOnClickListener {
            startActivity(com.aheadt1d.app.events.EventHistoryActivity.createIntent(this))
        }
    }

    // ===================== Plateau stress test =====================

    private fun setupPlateauTest() {
        findViewById<Button>(R.id.injectPlateauScenarioButton).setOnClickListener {
            stopScenario(null)
            val tuning = com.aheadt1d.app.tuning.PlateauTuningPrefs.load(this)
            // durationMinutes() (180) already covers the default lookback,
            // but a tester may have widened HIGH_DURATION/ESCALATION_STEP in
            // Tuning Parameters first - generate however much history that
            // now actually requires so the seeded data is never the limiting
            // factor.
            val minutes = maxOf(DebugScenario.SUSTAINED_HIGH_PLATEAU.durationMinutes(), tuning.lookbackMinutes())
            val points = flatPlateauPoints(minutes)
            DebugGlucoseOverride.setPoints(points)
            refreshSystemState()
            scenarioProgressText.text = "Injected ${points.size} flat-high point(s) across ${minutes}m - queuing a Check now cycle..."
            com.aheadt1d.app.work.WorkScheduler.runOnce(applicationContext)
        }
        findViewById<Button>(R.id.simulateLogCorrectionButton).setOnClickListener {
            com.aheadt1d.app.alerts.PlateauCoordinator.onCorrectionLogged(this)
            scenarioProgressText.text = "Simulated a correction log - the next Check now cycle will evaluate the response window."
        }
    }

    /** Same jittered-flat shape as DebugScenario.SUSTAINED_HIGH_PLATEAU.values(),
     *  but spanning an arbitrary [minutes] instead of that scenario's fixed
     *  180 - lets this button always cover whatever the CURRENT tuning's
     *  lookbackMinutes() needs, even after a tester widens HIGH_DURATION. */
    private fun flatPlateauPoints(minutes: Long): List<com.aheadt1d.app.health.GlucosePoint> {
        val stepMinutes = DebugScenario.STEP_MINUTES
        val steps = (minutes / stepMinutes).toInt()
        val now = java.time.Instant.now()
        val start = now.minus(Duration.ofMinutes(steps * stepMinutes))
        return (0..steps).map { i ->
            com.aheadt1d.app.health.GlucosePoint(start.plus(Duration.ofMinutes(i * stepMinutes)), 320 + ((i * 7) % 5) - 2)
        }
    }

    private fun staleThresholdMinutesSafe(): Long =
        com.aheadt1d.app.state.staleThresholdMinutes(this)

    private fun injectManual(ageMinOverride: Long?) {
        val value = manualValueInput.text.toString().toIntOrNull() ?: return
        val rate = manualRateInput.text.toString().toDoubleOrNull() ?: 0.0
        val ageMin = (ageMinOverride ?: manualAgeInput.text.toString().toLongOrNull() ?: 0L).toInt()

        // Build a short two-point series (5 min apart) ending at `value` so the
        // chart shows a real segment, not an isolated dot.
        val now = java.time.Instant.now().minusSeconds(ageMin * 60L)
        val prevValue = (value - (rate * 5)).toInt().coerceIn(20, 500)
        val points = listOf(
            com.aheadt1d.app.health.GlucosePoint(now.minus(Duration.ofMinutes(5)), prevValue),
            com.aheadt1d.app.health.GlucosePoint(now, value)
        )
        DebugGlucoseOverride.setPoints(points)

        val severity = simpleSeverityFor(value)
        DebugInjection.apply(this, severity, value, projected = null, projectedExtended = null, rate = rate, ageMin = ageMin)
        refreshSystemState()
        scenarioProgressText.text = "Injected $value mg/dL, rate ${"%.1f".format(rate)}, age ${ageMin}m"
        openRedAlertScreenIfNeeded(severity, value, projected = null, rate = rate)
    }

    /**
     * Directly starts RedAlertActivity instead of relying on the posted
     * notification's full-screen intent to launch it. FSI degrades to a
     * plain heads-up notification whenever the device is already in active
     * use (screen on, app foregrounded) - which is exactly the state you're
     * in while testing from this menu - so without this, injecting a red
     * reading here would update the dashboard/notification but the
     * Emergency Contact shield button (which only lives on RedAlertActivity)
     * would never actually become reachable.
     */
    private fun openRedAlertScreenIfNeeded(severity: String, value: Int, projected: Int?, rate: Double?) {
        if (severity != "red") return
        startActivity(RedAlertActivity.createIntent(this, value, projected, rate))
    }

    private fun playScenario() {
        stopScenario(null)
        val scenario = DebugScenario.values()[scenarioSpinner.selectedItemPosition]
        val speedFactor = if (speed10x.isChecked) 10.0 else 1.0
        val fullSeries = scenario.points()

        var redAlertOpened = false
        scenarioJob = lifecycleScope.launch {
            for (i in fullSeries.indices) {
                val visible = fullSeries.subList(0, i + 1)
                DebugGlucoseOverride.setPoints(visible)
                val latest = visible.last()
                val rate = HealthConnectManager.calculateRatePerMinute(visible) ?: 0.0
                val severity = simpleSeverityFor(latest.sgv)
                // Flatline scenario deliberately stops pushing fresh repo updates
                // partway through so the real staleness path can be exercised
                // without also having to wait out the full 5-min-per-point delay.
                if (scenario != DebugScenario.FLATLINE_STALE || i == 0) {
                    DebugInjection.apply(this@DebugMenuActivity, severity, latest.sgv, null, null, rate)
                }
                // Only pop the red-alert screen once per playback (on the first
                // red point), not on every subsequent tick - otherwise a
                // scenario that stays red for many points would relaunch the
                // activity repeatedly.
                if (!redAlertOpened) {
                    openRedAlertScreenIfNeeded(severity, latest.sgv, projected = null, rate = rate)
                    if (severity == "red") redAlertOpened = true
                }
                scenarioProgressText.text =
                    "Playing ${scenario.label}: ${i + 1}/${fullSeries.size} (${latest.sgv} mg/dL)"
                if (i < fullSeries.size - 1) {
                    val realIntervalMs = Duration.between(latest.time, fullSeries[i + 1].time).toMillis()
                    delay((realIntervalMs / speedFactor).toLong())
                }
            }
            scenarioProgressText.text = "Finished ${scenario.label}"
        }
    }

    private fun stopScenario(message: String?) {
        scenarioJob?.cancel()
        scenarioJob = null
        if (message != null) scenarioProgressText.text = message
    }

    private fun injectRandomPoints() {
        val count = randomCountInput.text.toString().toIntOrNull() ?: 100
        val points = randomGlucosePoints(count, windowMinutes = 360)
        DebugGlucoseOverride.setPoints(points)
        points.lastOrNull()?.let {
            val rate = HealthConnectManager.calculateRatePerMinute(points) ?: 0.0
            val severity = simpleSeverityFor(it.sgv)
            DebugInjection.apply(this, severity, it.sgv, null, null, rate)
            openRedAlertScreenIfNeeded(severity, it.sgv, projected = null, rate = rate)
        }
        refreshSystemState()
        scenarioProgressText.text = "Injected ${points.size} random point(s) across 6 hours"
    }

    /** Rough value-only bucketing for test purposes - NOT the real backend
     *  projection-based severity calc, which this menu exists to bypass. */
    private fun simpleSeverityFor(sgv: Int): String = when {
        sgv <= 70 || sgv >= 250 -> "red"
        sgv < 90 || sgv > 180 -> "yellow"
        else -> "none"
    }

    // ===================== Notification testing =====================

    private fun setupNotificationTesting() {
        findViewById<Button>(R.id.forceYellowButton).setOnClickListener {
            AlertNotifier.showYellowAlert(this, value = 150, projected = 172, rate = 1.2)
            afterForcedAlert()
        }
        findViewById<Button>(R.id.forceRedButton).setOnClickListener {
            // Unconditional post first - this button's whole point is to
            // force-fire regardless of AlertCoordinator's dedup/cooldown.
            AlertNotifier.showRedAlert(this, value = 58, projected = 48, rate = -2.5)
            // Also sync the dashboard/chart to the same value (via the normal
            // DebugGlucoseOverride + repo path) and open RedAlertActivity
            // directly - otherwise this button posts a notification whose
            // number never appears on the dashboard, and whose full-screen
            // intent silently degrades to a heads-up notification while the
            // app is foregrounded, so the Emergency Contact shield button is
            // never actually reachable.
            DebugGlucoseOverride.setPoints(
                listOf(
                    com.aheadt1d.app.health.GlucosePoint(java.time.Instant.now().minus(Duration.ofMinutes(5)), 65),
                    com.aheadt1d.app.health.GlucosePoint(java.time.Instant.now(), 58)
                )
            )
            DebugInjection.apply(this, "red", 58, projected = 48, projectedExtended = 48, rate = -2.5)
            startActivity(RedAlertActivity.createIntent(this, 58, 48, -2.5))
            afterForcedAlert()
        }
        findViewById<Button>(R.id.forceSignalLostButton).setOnClickListener {
            AlertNotifier.showSignalLostAlert(
                this,
                lastValue = 65,
                lastArrow = GlucoseTrendArrow.fromRatePerMinute(-2.0),
                ageMinutes = 20
            )
            // Signal-lost is now full red-tier delivery (see AlertNotifier) -
            // directly start the takeover screen's signal-lost variant too,
            // same reasoning as forceRedButton below: the FSI degrades to a
            // plain heads-up while the device is already in active use,
            // which is exactly the state you're in while testing from here.
            startActivity(RedAlertActivity.createSignalLostIntent(this, 65, GlucoseTrendArrow.fromRatePerMinute(-2.0), 20))
            afterForcedAlert()
        }
        findViewById<Button>(R.id.cancelAlertsButton).setOnClickListener {
            AlertNotifier.cancelAlerts(this)
        }

        voiceMasterSwitch.isChecked = VoiceAlertPrefs.isMasterEnabled(this)
        voiceMasterSwitch.setOnCheckedChangeListener { _, checked ->
            VoiceAlertPrefs.setMasterEnabled(this, checked)
        }

        disableFullScreenSwitch.isChecked = DebugAlertPrefs.isFullScreenDisabled(this)
        disableFullScreenSwitch.setOnCheckedChangeListener { _, checked ->
            DebugAlertPrefs.setFullScreenDisabled(this, checked)
        }
    }

    private fun afterForcedAlert() {
        if (autoBackgroundSwitch.isChecked) moveTaskToBack(true)
    }

    // ===================== Chart / UI testing =====================

    private fun setupChartTesting() {
        findViewById<Button>(R.id.openGraphButton).setOnClickListener {
            startActivity(Intent(this, GraphActivity::class.java))
        }
    }

    // ===================== System state =====================

    private fun setupSystemState() {
        findViewById<Button>(R.id.refreshStatusButton).setOnClickListener { refreshSystemState() }
        refreshSystemState()
    }

    /** Colors a status readout green/red by whether the underlying state is
     *  the healthy one - lets the SYSTEM STATE card be scanned at a glance
     *  instead of read line by line. Amber marks "injected", which isn't
     *  bad, just worth noticing (it means the chart isn't showing real data). */
    private fun setStatus(view: TextView, text: String, colorRes: Int) {
        view.text = text
        view.setTextColor(getColor(colorRes))
    }

    private fun refreshSystemState() {
        val pm = getSystemService(PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        setStatus(
            batteryStatusText,
            if (batteryOk) "Unrestricted" else "Restricted",
            if (batteryOk) R.color.ok else R.color.low
        )

        val nm = getSystemService(NotificationManager::class.java)
        val dndOk = nm.isNotificationPolicyAccessGranted
        setStatus(dndStatusText, if (dndOk) "Granted" else "Not granted", if (dndOk) R.color.ok else R.color.low)

        val injected = DebugGlucoseOverride.isActive
        setStatus(
            injectionStatusText,
            if (injected) "Injected (test data)" else "Real Health Connect",
            if (injected) R.color.high else R.color.ok
        )

        lifecycleScope.launch {
            val granted = runCatching {
                HealthConnectClient.getOrCreate(this@DebugMenuActivity).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            val ok = granted.containsAll(HealthConnectManager.ALL_PERMISSIONS)
            setStatus(
                hcPermsStatusText,
                if (ok) "All granted" else "Missing (${HealthConnectManager.ALL_PERMISSIONS - granted})",
                if (ok) R.color.ok else R.color.low
            )
        }
    }
}
