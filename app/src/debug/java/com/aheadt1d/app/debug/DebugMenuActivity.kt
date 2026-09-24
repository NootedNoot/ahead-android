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
import com.aheadt1d.app.alerts.AlertTones
import com.aheadt1d.app.health.HealthConnectManager
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import com.aheadt1d.app.state.DebugGlucoseOverride
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.voice.VoiceAlertPrefs
import com.aheadt1d.app.work.WorkScheduler
import androidx.core.content.ContextCompat
import com.aheadt1d.app.alerts.AlertSilenceManager
import androidx.core.content.edit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.aheadt1d.app.MainActivity
import com.aheadt1d.app.setup.SetupPrefs

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
    private lateinit var batteryStatusText: TextView
    private lateinit var hcPermsStatusText: TextView
    private lateinit var dndStatusText: TextView
    private lateinit var injectionStatusText: TextView
    private lateinit var debugEventTagSpinner: Spinner
    private lateinit var debugEventNoteInput: EditText
    private lateinit var debugEventHoursAgoInput: EditText
    private lateinit var silenceStatusText: TextView
    private lateinit var currentServerStatusText: TextView
    private lateinit var customServerUrlInput: EditText

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
        batteryStatusText = findViewById(R.id.batteryStatusText)
        hcPermsStatusText = findViewById(R.id.hcPermsStatusText)
        dndStatusText = findViewById(R.id.dndStatusText)
        injectionStatusText = findViewById(R.id.injectionStatusText)
        debugEventTagSpinner = findViewById(R.id.debugEventTagSpinner)
        debugEventNoteInput = findViewById(R.id.debugEventNoteInput)
        debugEventHoursAgoInput = findViewById(R.id.debugEventHoursAgoInput)
        silenceStatusText = findViewById(R.id.silenceStatusText)
        currentServerStatusText = findViewById(R.id.currentServerStatusText)
        customServerUrlInput = findViewById(R.id.customServerUrlInput)

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

        setupSilenceKillswitch()
        setupResetAll()
        setupGlucoseInjection()
        setupNotificationTesting()
        setupServerConfig()
        setupChartTesting()
        setupSystemState()
        setupNotesHistoryTest()
        setupPlateauTest()
        findViewById<android.view.View>(R.id.backButton)?.setOnClickListener { finish() }
        findViewById<Button>(R.id.openTuningButton)?.setOnClickListener {
            startActivity(Intent(this, TuningActivity::class.java))
        }
        findViewById<Button>(R.id.resetWizardButton)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Setup Wizard?")
                .setMessage("This will reset your onboarding preferences and restart the setup wizard from Step 1. Your historical database and server credentials remain intact. Continue?")
                .setPositiveButton("Reset & Restart") { _, _ ->
                    SetupPrefs.resetWizardState(this)
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<Button>(R.id.closeButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemState()
        updateSilenceStatus()
    }

    private fun updateSilenceStatus() {
        val killSwitchOn = AlertSilenceManager.isDevKillSwitchActive(this)
        if (killSwitchOn) {
            silenceStatusText.text = "Status: 🛑 DEV KILL SWITCH ACTIVE — everything blocked, including Alarm Thresholds"
            silenceStatusText.setTextColor(ContextCompat.getColor(this, R.color.low))
        } else if (AlertSilenceManager.isSilenced(this)) {
            val desc = AlertSilenceManager.getSilenceDescription(this)
            silenceStatusText.text = "Status: 🔕 $desc"
            silenceStatusText.setTextColor(ContextCompat.getColor(this, R.color.low))
        } else {
            silenceStatusText.text = "Status: Alerts Active (Normal)"
            silenceStatusText.setTextColor(ContextCompat.getColor(this, R.color.ok))
        }

        findViewById<Button>(R.id.devKillSwitchButton).text =
            if (killSwitchOn) "✅ Disable Dev Kill Switch" else "🛑 Enable Dev Kill Switch"
    }

    private fun setupSilenceKillswitch() {
        updateSilenceStatus()

        findViewById<Button>(R.id.silencePermanentButton).setOnClickListener {
            AlertSilenceManager.silenceIndefinitely(this)
            updateSilenceStatus()
        }
        findViewById<Button>(R.id.silence10mButton).setOnClickListener {
            AlertSilenceManager.silence(this, 10)
            updateSilenceStatus()
        }
        findViewById<Button>(R.id.silence15mButton).setOnClickListener {
            AlertSilenceManager.silence(this, 15)
            updateSilenceStatus()
        }
        findViewById<Button>(R.id.silence30mButton).setOnClickListener {
            AlertSilenceManager.silence(this, 30)
            updateSilenceStatus()
        }
        findViewById<Button>(R.id.silence60mButton).setOnClickListener {
            AlertSilenceManager.silence(this, 60)
            updateSilenceStatus()
        }
        findViewById<Button>(R.id.cancelSilenceButton).setOnClickListener {
            AlertSilenceManager.cancelSilence(this)
            updateSilenceStatus()
        }
        findViewById<Button>(R.id.devKillSwitchButton).setOnClickListener {
            AlertSilenceManager.setDevKillSwitch(this, !AlertSilenceManager.isDevKillSwitchActive(this))
            updateSilenceStatus()
        }
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
            DebugGlucoseOverride.notifyStateChanged(this)
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.aheadt1d.app.network.BackendClient.deleteRecentReadings(applicationContext)
            }

            AlertNotifier.cancelAlerts(this)
            AlertNotifier.cancelPlateau(this)
            AlertNotifier.cancelCorrection(this)
            // Same reasoning as the ahead_alert_state/ahead_plateau_state
            // clears right below: leftover fired-state from a debug
            // injection would otherwise quietly skew how the NEXT real
            // reading gets evaluated. Clears only currentlyCrossed/
            // lastFiredAtMs/lastFiredAtMetric - actual threshold
            // configuration (kind/direction/amount/label/enabled) is real
            // user data and is left untouched, matching how this same
            // button already leaves Voice Alert settings alone.
            com.aheadt1d.app.alerts.CustomThresholdStore.load(this).forEach {
                com.aheadt1d.app.alerts.AlertNotifier.cancelCustomThreshold(this, it.id)
            }
            com.aheadt1d.app.alerts.CustomThresholdStore.resetFiredState(this)

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
        findViewById<Button>(R.id.stepScenarioButton).setOnClickListener { stepScenario() }
        findViewById<Button>(R.id.injectFullDemoButton).setOnClickListener { injectFullDemoScenario() }
        findViewById<Button>(R.id.stopScenarioButton).setOnClickListener { stopScenario("Playback stopped") }
        findViewById<Button>(R.id.injectRandomButton).setOnClickListener { injectRandomPoints() }
        findViewById<Button>(R.id.clearInjectionButton).setOnClickListener {
            stopScenario(null)
            DebugGlucoseOverride.clear()
            DebugGlucoseOverride.notifyStateChanged(this)
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.aheadt1d.app.network.BackendClient.deleteRecentReadings(applicationContext)
            }
            refreshSystemState()
            scenarioProgressText.text = "Injected data cleared - chart now reads real Health Connect."
        }
        findViewById<Button>(R.id.injectReportTestDataButton).setOnClickListener {
            stopScenario(null)
            val points = twoWeekReportTestPoints()
            DebugGlucoseOverride.setPoints(points)
            DebugGlucoseOverride.notifyStateChanged(this)
            refreshSystemState()
            scenarioProgressText.text =
                "Injected ${points.size} points across 14 days (6-day gap in the middle). Open Doctor Report and generate for the last 14 days to test."
        }
        findViewById<Button>(R.id.openDoctorReportButton).setOnClickListener {
            startActivity(com.aheadt1d.app.report.ReportExportActivity.createIntent(this))
        }

        findViewById<Button>(R.id.exportFullHistoryButton).setOnClickListener {
            exportFullHistory()
        }
    }

    /**
     * "Export everything, ever" - the whole GlucoseVaultDatabase, not one day
     * (ArchiveBrowserActivity) and not the doctor-facing AGP report. Same
     * FileProvider/ACTION_SEND_MULTIPLE share pattern ArchiveBrowserActivity
     * uses for its per-day export.
     */
    private fun exportFullHistory() {
        lifecycleScope.launch {
            val (jsonFile, csvFile) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.aheadt1d.app.data.FullHistoryExporter.exportAll(applicationContext)
            }
            if (jsonFile == null && csvFile == null) {
                android.widget.Toast.makeText(this@DebugMenuActivity, "Vault is empty - nothing to export", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val authority = "${com.aheadt1d.app.BuildConfig.APPLICATION_ID}.fileprovider"
            val uris = ArrayList<android.net.Uri>()
            jsonFile?.let { uris.add(androidx.core.content.FileProvider.getUriForFile(this@DebugMenuActivity, authority, it)) }
            csvFile?.let { uris.add(androidx.core.content.FileProvider.getUriForFile(this@DebugMenuActivity, authority, it)) }

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "Ahead full history export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share full history export"))
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
            DebugGlucoseOverride.notifyStateChanged(this)
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
        DebugGlucoseOverride.notifyStateChanged(this)

        val severity = simpleSeverityFor(value)
        DebugInjection.apply(this, severity, value, projected = null, projectedExtended = null, rate = rate, ageMin = ageMin)
        refreshSystemState()
        scenarioProgressText.text = "Injected $value mg/dL, rate ${"%.1f".format(rate)}, age ${ageMin}m"
    }

    private var currentScenarioStep = -1

    private fun playScenario() {
        stopScenario(null)
        val scenario = DebugScenario.values()[scenarioSpinner.selectedItemPosition]
        val speedFactor = if (speed10x.isChecked) 10.0 else 1.0
        val fullSeries = scenario.points()

        scenarioJob = lifecycleScope.launch {
            for (i in fullSeries.indices) {
                currentScenarioStep = i
                applyScenarioStep(scenario, fullSeries, i)
                if (i < fullSeries.size - 1) {
                    val realIntervalMs = Duration.between(fullSeries[i].time, fullSeries[i + 1].time).toMillis()
                    delay((realIntervalMs / speedFactor).toLong())
                }
            }
            val desc = scenario.demoDescription?.let { "\n$it" } ?: ""
            scenarioProgressText.text = "Finished ${scenario.label}$desc"
        }
    }

    private fun stepScenario() {
        val scenario = DebugScenario.values()[scenarioSpinner.selectedItemPosition]
        val fullSeries = scenario.points()
        currentScenarioStep = (currentScenarioStep + 1) % fullSeries.size
        applyScenarioStep(scenario, fullSeries, currentScenarioStep)
    }

    private fun injectFullDemoScenario() {
        stopScenario(null)
        val scenario = DebugScenario.values()[scenarioSpinner.selectedItemPosition]
        val fullSeries = scenario.points()
        val targetIndex = when (scenario) {
            DebugScenario.DEMO_PREDICTIVE_HYPO_CATCH -> 4 // 96 mg/dL, rate -2.2/m, early warning
            DebugScenario.DEMO_TREATED_RECOVERY_SMART_MUTE -> 3 // 78 mg/dL, rising, treated tier
            DebugScenario.DEMO_UNEXPLAINED_FALSE_REBOUND -> 3 // 88 mg/dL peak of bounce, unexplained tier
            DebugScenario.DEMO_POST_MEAL_INSULIN_DECAY -> 5 // 230 mg/dL peak rollover
            DebugScenario.DEMO_DELAYED_EXERCISE_RISK -> 5 // 86 mg/dL nocturnal drop
            else -> fullSeries.size - 1
        }
        currentScenarioStep = targetIndex
        applyScenarioStep(scenario, fullSeries, targetIndex)
    }

    private fun applyScenarioStep(scenario: DebugScenario, fullSeries: List<com.aheadt1d.app.health.GlucosePoint>, i: Int) {
        val visible = fullSeries.subList(0, i + 1)
        DebugGlucoseOverride.setPoints(visible)
        DebugGlucoseOverride.notifyStateChanged(this@DebugMenuActivity)
        val latest = visible.last()
        val rate = scenario.customRateForPoint(i, fullSeries.size)
            ?: (HealthConnectManager.calculateRatePerMinute(visible) ?: 0.0)
        val severity = scenario.customSeverityForPoint(i, fullSeries.size, latest.sgv)
            ?: simpleSeverityFor(latest.sgv)
        val causeTier = scenario.causeTierForPoint(i, fullSeries.size)
        val projectedPair = scenario.customProjectedForPoint(i, fullSeries.size, latest.sgv, rate)
        val projected = projectedPair?.first
        val projectedExtended = projectedPair?.second

        if (scenario != DebugScenario.FLATLINE_STALE || i == 0) {
            DebugInjection.apply(
                this@DebugMenuActivity,
                severity = severity,
                value = latest.sgv,
                projected = projected,
                projectedExtended = projectedExtended,
                rate = rate,
                causeTier = causeTier
            )
        }
        val tierStr = causeTier?.let { " [Tier: ${it.name}]" } ?: ""
        val desc = scenario.demoDescription?.let { "\n$it" } ?: ""
        scenarioProgressText.text =
            "Step ${i + 1}/${fullSeries.size}: ${latest.sgv} mg/dL, ${"%.1f".format(rate)}/m, sev: $severity$tierStr$desc"
    }

    private fun setupServerConfig() {
        refreshServerConfig()
        findViewById<Button>(R.id.setCustomServerButton).setOnClickListener {
            val url = customServerUrlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                com.aheadt1d.app.network.ServerConfig.setCustomBaseUrl(this, url)
                refreshServerConfig()
                scenarioProgressText.text = "Custom backend URL applied: $url"
            }
        }
        findViewById<Button>(R.id.resetServerButton).setOnClickListener {
            com.aheadt1d.app.network.ServerConfig.setCustomBaseUrl(this, null)
            customServerUrlInput.setText("")
            refreshServerConfig()
            scenarioProgressText.text = "Reset backend to default URL."
        }
    }

    private fun refreshServerConfig() {
        val current = com.aheadt1d.app.network.ServerConfig.getBaseUrl(this)
        val isCustom = com.aheadt1d.app.network.ServerConfig.isCustomUrl(this)
        currentServerStatusText.text = if (isCustom) "Active Server (CUSTOM): $current" else "Active Server (DEFAULT): $current"
        if (isCustom && customServerUrlInput.text.isNullOrBlank()) {
            customServerUrlInput.setText(current)
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
        findViewById<Button>(R.id.forceYellowLowButton).setOnClickListener {
            AlertNotifier.showYellowAlert(this, value = 85, projected = 75, rate = -1.5, projectedExtended = 65, isInjected = true)
            afterForcedAlert()
        }
        findViewById<Button>(R.id.forceYellowHighButton).setOnClickListener {
            AlertNotifier.showYellowAlert(this, value = 150, projected = 172, rate = 1.2, projectedExtended = 195, isInjected = true)
            afterForcedAlert()
        }
        findViewById<Button>(R.id.playWarnLowButton).setOnClickListener {
            AlertTones.play(this, AlertTones.Tone.WARN_LOW)
        }
        findViewById<Button>(R.id.playWarnHighButton).setOnClickListener {
            AlertTones.play(this, AlertTones.Tone.WARN_HIGH)
        }
        findViewById<Button>(R.id.playCalmLowButton).setOnClickListener {
            AlertTones.play(this, AlertTones.Tone.CALM_LOW)
        }
        findViewById<Button>(R.id.playCalmHighButton).setOnClickListener {
            AlertTones.play(this, AlertTones.Tone.CALM_HIGH)
        }
        findViewById<Button>(R.id.playUrgentLowButton).setOnClickListener {
            AlertTones.play(this, AlertTones.Tone.URGENT_LOW)
        }
        findViewById<Button>(R.id.playUrgentHighButton).setOnClickListener {
            AlertTones.play(this, AlertTones.Tone.URGENT_HIGH)
        }
        findViewById<Button>(R.id.playSignalLostButton).setOnClickListener {
            AlertTones.play(this, AlertTones.Tone.SIGNAL_LOST)
        }
        findViewById<Button>(R.id.forceRedButton).setOnClickListener {
            // Unconditional post first - this button's whole point is to
            // force-fire regardless of AlertCoordinator's dedup/cooldown.
            AlertNotifier.showRedAlert(this, value = 58, projected = 48, rate = -2.5, isInjected = true)
            // Also sync the dashboard/chart to the same value (via the normal
            // DebugGlucoseOverride + repo path), so this button posts a
            // notification whose number also appears on the dashboard.
            DebugGlucoseOverride.setPoints(
                listOf(
                    com.aheadt1d.app.health.GlucosePoint(java.time.Instant.now().minus(Duration.ofMinutes(5)), 65),
                    com.aheadt1d.app.health.GlucosePoint(java.time.Instant.now(), 58)
                )
            )
            DebugInjection.apply(this, "red", 58, projected = 48, projectedExtended = 48, rate = -2.5)
            afterForcedAlert()
        }
        findViewById<Button>(R.id.forceSignalLostButton).setOnClickListener {
            AlertNotifier.showSignalLostAlert(
                this,
                lastValue = 65,
                lastArrow = GlucoseTrendArrow.fromRatePerMinute(-2.0),
                ageMinutes = 20,
                isInjected = true
            )
            afterForcedAlert()
        }
        findViewById<Button>(R.id.cancelAlertsButton).setOnClickListener {
            AlertNotifier.cancelAlerts(this)
            AlertNotifier.cancelPlateau(this)
            AlertNotifier.cancelCorrection(this)
            com.aheadt1d.app.alerts.CustomThresholdStore.load(this).forEach {
                com.aheadt1d.app.alerts.AlertNotifier.cancelCustomThreshold(this, it.id)
            }
        }

        voiceMasterSwitch.isChecked = VoiceAlertPrefs.isMasterEnabled(this)
        voiceMasterSwitch.setOnCheckedChangeListener { _, checked ->
            VoiceAlertPrefs.setMasterEnabled(this, checked)
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
