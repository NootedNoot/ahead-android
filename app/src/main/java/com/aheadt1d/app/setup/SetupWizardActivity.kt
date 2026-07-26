package com.aheadt1d.app.setup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.aheadt1d.app.MainActivity
import com.aheadt1d.app.R
import com.aheadt1d.app.alerts.AlertChannels
import com.aheadt1d.app.health.HealthConnectManager
import com.aheadt1d.app.state.isStale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Guided first-launch setup. One activity, one ViewFlipper, eight linear
 * steps. Branching (Dexcom vs Juggluco vs unsure) changes copy, never the
 * step sequence. Every step is skippable and the whole wizard is skippable;
 * it's idempotent and can be re-run later via an explicit intent.
 *
 * Portrait-locked in the manifest so nothing (least of all the step-5 polling
 * job) restarts on rotation; onSaveInstanceState still covers process death,
 * since ViewFlipper doesn't persist its displayedChild.
 */
class SetupWizardActivity : AppCompatActivity() {

    private lateinit var flipper: ViewFlipper
    private lateinit var dots: LinearLayout
    private lateinit var backButton: TextView
    private lateinit var skipStepButton: TextView
    private lateinit var continueButton: Button

    private var chosenPath: String = SetupPrefs.PATH_UNSURE
    private var verifiedValue: Int? = null
    private var verifyJob: Job? = null

    private val stepCount get() = flipper.childCount
    private val currentStep get() = flipper.displayedChild

    private val requestHcPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refreshStep() }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStep() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)

        flipper = findViewById(R.id.view_flipper)
        dots = findViewById(R.id.ll_dots)
        backButton = findViewById(R.id.btn_back)
        skipStepButton = findViewById(R.id.btn_skip_step)
        continueButton = findViewById(R.id.btn_continue)

        buildDots()
        wireNav()
        wireStepActions()

        chosenPath = savedInstanceState?.getString(STATE_PATH) ?: detectPath()
        verifiedValue = savedInstanceState?.takeIf { it.containsKey(STATE_VERIFIED) }?.getInt(STATE_VERIFIED)
        val step = savedInstanceState?.getInt(STATE_STEP) ?: 0
        flipper.displayedChild = step

        refreshStep()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, currentStep)
        outState.putString(STATE_PATH, chosenPath)
        verifiedValue?.let { outState.putInt(STATE_VERIFIED, it) }
    }

    override fun onResume() {
        super.onResume()
        refreshStep()
    }

    override fun onPause() {
        super.onPause()
        stopVerifyPolling()
    }

    // ---- navigation ----

    private fun wireNav() {
        findViewById<TextView>(R.id.btn_skip_all).setOnClickListener { goTo(stepCount - 1) }
        backButton.setOnClickListener {
            if (currentStep == 0) finish() else goTo(currentStep - 1)
        }
        skipStepButton.setOnClickListener { advance() }
        continueButton.setOnClickListener {
            if (currentStep == stepCount - 1) finishWizard() else advance()
        }
    }

    private fun advance() {
        if (currentStep < stepCount - 1) goTo(currentStep + 1) else finishWizard()
    }

    private fun goTo(step: Int) {
        stopVerifyPolling()
        flipper.displayedChild = step.coerceIn(0, stepCount - 1)
        refreshStep()
    }

    private fun finishWizard() {
        SetupPrefs.setComplete(this, true)
        SetupPrefs.setCgmPath(this, chosenPath)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun buildDots() {
        val size = resources.displayMetrics.density.times(8).toInt()
        val margin = resources.displayMetrics.density.times(4).toInt()
        for (i in 0 until 8) {
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = margin
            dot.layoutParams = lp
            dots.addView(dot)
        }
    }

    // ---- per-step refresh ----

    private fun refreshStep() {
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).setBackgroundResource(
                if (i == currentStep) R.drawable.wizard_dot_active else R.drawable.wizard_dot_inactive
            )
        }

        backButton.visibility = if (currentStep == 0) View.INVISIBLE else View.VISIBLE
        val onDone = currentStep == stepCount - 1
        skipStepButton.visibility = if (onDone) View.INVISIBLE else View.VISIBLE
        continueButton.text = getString(if (onDone) R.string.wizard_done_cta else R.string.wizard_continue)
        continueButton.isEnabled = true

        when (currentStep) {
            0 -> refreshPath()
            1 -> refreshHcInstall()
            2 -> refreshHcPerms()
            3 -> refreshSource()
            4 -> refreshVerify()
            5 -> refreshBattery()
            6 -> refreshNotifs()
        }
    }

    private fun refreshPath() {
        val map = mapOf(
            R.id.btn_path_dexcom to SetupPrefs.PATH_DEXCOM,
            R.id.btn_path_juggluco to SetupPrefs.PATH_JUGGLUCO,
            R.id.btn_path_unsure to SetupPrefs.PATH_UNSURE
        )
        map.forEach { (id, path) ->
            findViewById<TextView>(id).setBackgroundResource(
                if (path == chosenPath) R.drawable.wizard_option_selected
                else R.drawable.wizard_option_unselected
            )
        }
    }

    private fun refreshHcInstall() {
        val available = HealthConnectManager.isAvailable(this)
        setStatus(R.id.tv_hc_status, available,
            getString(R.string.wizard_hc_installed), getString(R.string.wizard_hc_missing))
        findViewById<Button>(R.id.btn_hc_install).visibility = if (available) View.GONE else View.VISIBLE
    }

    private fun refreshHcPerms() {
        lifecycleScope.launch {
            val granted = runCatching {
                HealthConnectClient.getOrCreate(this@SetupWizardActivity)
                    .permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            val ok = granted.containsAll(HealthConnectManager.ALL_PERMISSIONS)
            setStatus(R.id.tv_perms_status, ok,
                getString(R.string.wizard_perms_granted), getString(R.string.wizard_perms_missing))
            findViewById<Button>(R.id.btn_perms_grant).visibility = if (ok) View.GONE else View.VISIBLE
        }
    }

    private fun refreshSource() {
        val body = findViewById<TextView>(R.id.tv_source_body)
        body.text = getString(
            when (chosenPath) {
                SetupPrefs.PATH_DEXCOM -> R.string.wizard_source_dexcom
                SetupPrefs.PATH_JUGGLUCO -> R.string.wizard_source_juggluco
                else -> R.string.wizard_source_unsure
            }
        )
        toggleOpenButton(R.id.btn_source_dexcom, DEXCOM_G7, DEXCOM_G6,
            show = chosenPath != SetupPrefs.PATH_JUGGLUCO)
        toggleOpenButton(R.id.btn_source_juggluco, JUGGLUCO, null,
            show = chosenPath != SetupPrefs.PATH_DEXCOM)
    }

    private fun toggleOpenButton(buttonId: Int, pkg: String, fallbackPkg: String?, show: Boolean) {
        val button = findViewById<Button>(buttonId)
        val launch = launchIntentFor(pkg) ?: fallbackPkg?.let { launchIntentFor(it) }
        button.visibility = if (show && launch != null) View.VISIBLE else View.GONE
        button.setOnClickListener { launch?.let { startActivity(it) } }
    }

    private fun refreshVerify() {
        val done = verifiedValue != null
        findViewById<View>(R.id.pb_verify).visibility = if (done) View.GONE else View.VISIBLE
        val status = findViewById<TextView>(R.id.tv_verify_status)
        if (done) {
            status.text = getString(R.string.wizard_verify_got_it, verifiedValue)
            status.setTextColor(ContextCompat.getColor(this, R.color.ok))
        } else {
            status.text = getString(R.string.wizard_verify_waiting)
            status.setTextColor(ContextCompat.getColor(this, R.color.ink_light))
            // Continue stays available (per spec Skip is always there), but the
            // spinner keeps running until a reading lands or the user leaves.
            startVerifyPolling()
        }
    }

    private fun startVerifyPolling() {
        if (verifyJob?.isActive == true) return
        verifyJob = lifecycleScope.launch {
            while (isActive && verifiedValue == null) {
                val points = runCatching {
                    HealthConnectManager.readGlucosePoints(this@SetupWizardActivity, 30)
                }.getOrDefault(emptyList())
                val latest = points.lastOrNull()
                if (latest != null && !isStale(this@SetupWizardActivity, latest.time.toEpochMilli())) {
                    verifiedValue = latest.sgv
                    if (currentStep == 4) refreshVerify()
                    return@launch
                }
                delay(10_000)
            }
        }
    }

    private fun stopVerifyPolling() {
        verifyJob?.cancel()
        verifyJob = null
    }

    private fun refreshBattery() {
        val pm = getSystemService(PowerManager::class.java)
        val ok = pm.isIgnoringBatteryOptimizations(packageName)
        setStatus(R.id.tv_battery_status, ok,
            getString(R.string.wizard_battery_granted), getString(R.string.wizard_battery_missing))
        findViewById<Button>(R.id.btn_battery).visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun refreshNotifs() {
        val nm = getSystemService(android.app.NotificationManager::class.java)

        val postOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        setStatus(R.id.tv_notif_post_status, postOk, getString(R.string.wizard_notif_post), getString(R.string.wizard_notif_post))
        findViewById<Button>(R.id.btn_notif_post).visibility = if (postOk) View.GONE else View.VISIBLE

        val fsiOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm.canUseFullScreenIntent()
        setStatus(R.id.tv_notif_fsi_status, fsiOk, getString(R.string.wizard_notif_fsi), getString(R.string.wizard_notif_fsi))
        findViewById<Button>(R.id.btn_notif_fsi).visibility =
            if (fsiOk || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) View.GONE else View.VISIBLE

        val dndOk = nm.isNotificationPolicyAccessGranted
        setStatus(R.id.tv_notif_dnd_status, dndOk, getString(R.string.wizard_notif_dnd), getString(R.string.wizard_notif_dnd))
        findViewById<Button>(R.id.btn_notif_dnd).visibility = if (dndOk) View.GONE else View.VISIBLE
        // Returning here with policy access freshly granted is what lets the
        // red channel migrate to a DND-bypassing version.
        if (dndOk) AlertChannels.ensure(this)
    }

    // ---- step action wiring (one-time listeners) ----

    private fun wireStepActions() {
        findViewById<TextView>(R.id.btn_path_dexcom).setOnClickListener { selectPath(SetupPrefs.PATH_DEXCOM) }
        findViewById<TextView>(R.id.btn_path_juggluco).setOnClickListener { selectPath(SetupPrefs.PATH_JUGGLUCO) }
        findViewById<TextView>(R.id.btn_path_unsure).setOnClickListener { selectPath(SetupPrefs.PATH_UNSURE) }

        findViewById<Button>(R.id.btn_hc_install).setOnClickListener { openPlayStore(HEALTH_CONNECT_PKG) }

        findViewById<Button>(R.id.btn_perms_grant).setOnClickListener {
            requestHcPermissions.launch(HealthConnectManager.ALL_PERMISSIONS)
        }
        findViewById<Button>(R.id.btn_perms_settings).setOnClickListener {
            runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
        }

        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        }

        findViewById<Button>(R.id.btn_notif_post).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        findViewById<Button>(R.id.btn_notif_fsi).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                    )
                }
            }
        }
        findViewById<Button>(R.id.btn_notif_dnd).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        }
    }

    private fun selectPath(path: String) {
        chosenPath = path
        SetupPrefs.setCgmPath(this, path)
        refreshPath()
    }

    // ---- helpers ----

    private fun setStatus(viewId: Int, ok: Boolean, okText: String, missingText: String) {
        val view = findViewById<TextView>(viewId)
        view.text = if (ok) "✓ $okText" else "✗ $missingText"
        view.setTextColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.coral))
    }

    private fun launchIntentFor(pkg: String): Intent? = packageManager.getLaunchIntentForPackage(pkg)

    private fun detectPath(): String = when {
        launchIntentFor(DEXCOM_G7) != null || launchIntentFor(DEXCOM_G6) != null -> SetupPrefs.PATH_DEXCOM
        launchIntentFor(JUGGLUCO) != null -> SetupPrefs.PATH_JUGGLUCO
        else -> SetupPrefs.PATH_UNSURE
    }

    private fun openPlayStore(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }

    companion object {
        private const val STATE_STEP = "step"
        private const val STATE_PATH = "path"
        private const val STATE_VERIFIED = "verified"

        private const val HEALTH_CONNECT_PKG = "com.google.android.apps.healthdata"
        private const val DEXCOM_G6 = "com.dexcom.g6"
        private const val DEXCOM_G7 = "com.dexcom.g7"
        private const val JUGGLUCO = "tk.glucodata"

        fun createIntent(context: Context): Intent =
            Intent(context, SetupWizardActivity::class.java)
    }
}
