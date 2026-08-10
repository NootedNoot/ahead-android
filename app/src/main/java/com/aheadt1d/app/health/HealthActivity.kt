package com.aheadt1d.app.health

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.aheadt1d.app.R
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Read-only "what's Ahead seeing besides glucose" screen (2026-08-03) - steps,
 * exercise sessions, and sleep, for TODAY only (local midnight to now).
 * Deliberately display-only for now: nothing here feeds the alert pipeline
 * or the guess engine.
 *
 * Steps and exercise/sleep come from two ENTIRELY different sources, each
 * with its own permission system:
 *  - Steps: [StepTracker], reading the phone's own step-counter sensor
 *    directly (Manifest.permission.ACTIVITY_RECOGNITION, a plain runtime
 *    permission). Built after discovering Samsung Health wasn't actually
 *    syncing steps into Health Connect at all on the device this was built
 *    against - the original HC-based steps read was silently always empty.
 *  - Exercise/sleep: still read from Health Connect
 *    ([HealthConnectManager.ACTIVITY_PERMISSIONS], its own permission
 *    controller flow) - genuinely need another app (Samsung Health, a
 *    smartwatch companion app, etc.) to have detected and logged them; there's
 *    no on-device equivalent for "was this a workout" or "were you asleep"
 *    the way there is for a plain step count.
 */
class HealthActivity : AppCompatActivity() {

    private lateinit var permissionCard: View
    private lateinit var permissionStatusText: TextView
    private lateinit var stepsValueText: TextView
    private lateinit var enableStepsButton: View
    private lateinit var exerciseListContainer: LinearLayout
    private lateinit var sleepSummaryText: TextView

    private val requestActivityPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refresh() }

    private val requestStepPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Registers immediately rather than waiting for GlucoseStatusService's
        // next natural restart, so tracking starts the moment permission is
        // granted instead of only after the next service (re)start.
        if (granted) StepTracker.start(this)
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        permissionCard = findViewById(R.id.permissionCard)
        permissionStatusText = findViewById(R.id.permissionStatusText)
        stepsValueText = findViewById(R.id.stepsValueText)
        enableStepsButton = findViewById(R.id.enableStepsButton)
        exerciseListContainer = findViewById(R.id.exerciseListContainer)
        sleepSummaryText = findViewById(R.id.sleepSummaryText)

        findViewById<View>(R.id.grantPermissionsButton).setOnClickListener {
            requestActivityPermissions.launch(HealthConnectManager.ACTIVITY_PERMISSIONS)
        }
        enableStepsButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestStepPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        renderSteps()
        lifecycleScope.launch {
            val granted = HealthConnectManager.grantedActivityPermissions(this@HealthActivity)
            updatePermissionCard(granted)
            renderExercise(granted)
            renderSleep(granted)
        }
    }

    private fun updatePermissionCard(granted: Set<String>) {
        val missing = HealthConnectManager.ACTIVITY_PERMISSIONS - granted
        if (missing.isEmpty()) {
            permissionCard.visibility = View.GONE
            return
        }
        permissionCard.visibility = View.VISIBLE
        val missingLabels = buildList {
            if (HealthConnectManager.READ_EXERCISE_PERMISSION in missing) add("exercise")
            if (HealthConnectManager.READ_SLEEP_PERMISSION in missing) add("sleep")
        }
        permissionStatusText.text = "Missing: ${missingLabels.joinToString(", ")}. Steps are separate - see the button in that card below."
    }

    private fun hasStepPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun renderSteps() {
        if (!hasStepPermission()) {
            stepsValueText.text = "Permission needed"
            enableStepsButton.visibility = View.VISIBLE
            return
        }
        enableStepsButton.visibility = View.GONE
        if (!StepTracker.isAvailable(this)) {
            stepsValueText.text = "No step sensor on this device"
            return
        }
        // Make sure the listener's actually registered - covers the case
        // where permission was granted in a prior session and the service
        // hasn't been restarted since.
        StepTracker.start(this)
        val steps = StepTracker.todaySteps(this)
        stepsValueText.text = steps?.toString() ?: "Waiting for first step…"
    }

    private fun renderExercise(granted: Set<String>) {
        exerciseListContainer.removeAllViews()
        if (HealthConnectManager.READ_EXERCISE_PERMISSION !in granted) {
            exerciseListContainer.addView(rowText("Permission needed"))
            return
        }
        lifecycleScope.launch {
            val sessions = HealthConnectManager.readTodayExerciseSessions(this@HealthActivity).orEmpty()
            exerciseListContainer.removeAllViews()
            if (sessions.isEmpty()) {
                exerciseListContainer.addView(rowText("No workouts logged today"))
                return@launch
            }
            val zone = ZoneId.systemDefault()
            sessions.forEach { session ->
                val minutes = Duration.between(session.startTime, session.endTime).toMinutes()
                val label = session.title?.takeIf { it.isNotBlank() } ?: "Workout"
                val timeRange = "${TIME_FORMATTER.withZone(zone).format(session.startTime)} - " +
                    TIME_FORMATTER.withZone(zone).format(session.endTime)
                exerciseListContainer.addView(rowText("$label · $timeRange (${minutes}m)"))
            }
        }
    }

    private fun renderSleep(granted: Set<String>) {
        if (HealthConnectManager.READ_SLEEP_PERMISSION !in granted) {
            sleepSummaryText.text = "Permission needed"
            return
        }
        lifecycleScope.launch {
            val session = HealthConnectManager.readMostRecentSleepSession(this@HealthActivity)
            if (session == null) {
                sleepSummaryText.text = "No sleep data in the last 24h"
                return@launch
            }
            val zone = ZoneId.systemDefault()
            val hours = Duration.between(session.startTime, session.endTime).toMinutes() / 60.0
            sleepSummaryText.text = "${TIME_FORMATTER.withZone(zone).format(session.startTime)} - " +
                "${TIME_FORMATTER.withZone(zone).format(session.endTime)} (${"%.1f".format(hours)}h)"
        }
    }

    private fun rowText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@HealthActivity, R.color.text_primary))
        textSize = 14f
        setPadding(0, dpToPx(4), 0, dpToPx(4))
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")

        fun createIntent(context: Context): Intent = Intent(context, HealthActivity::class.java)
    }
}
