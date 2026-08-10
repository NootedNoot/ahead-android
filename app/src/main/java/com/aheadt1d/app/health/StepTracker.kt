package com.aheadt1d.app.health

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZoneId

/**
 * On-device step tracking via Android's own TYPE_STEP_COUNTER sensor (2026-08-03)
 * - built after discovering Samsung Health wasn't actually syncing steps into
 * Health Connect at all on Ryan's phone, so HealthActivity's original
 * HC-based steps read was silently always empty. This bypasses Samsung
 * Health (or any other app) entirely: TYPE_STEP_COUNTER is the same raw
 * hardware sensor those apps read from, available directly to any app that
 * asks.
 *
 * TYPE_STEP_COUNTER reports a cumulative count since the device's last boot,
 * not "since midnight" - this persists a baseline (the raw count at the
 * start of today) and reports todaySteps() as (latest raw - baseline),
 * re-baselining on both an ordinary day rollover and a device reboot (which
 * resets the sensor's own counter to 0 - detected here as "the new raw
 * value is LESS than the last one we saw", since that can only mean a
 * reboot, never a real backwards step count).
 *
 * Registered from GlucoseStatusService's onCreate/onDestroy rather than
 * owning a service of its own - registerListener only accumulates while
 * something is actively listening, and that service is already the one
 * thing in this app guaranteed to be running continuously. Needs
 * Manifest.permission.ACTIVITY_RECOGNITION (API 29+) to receive step-sensor
 * events at all - see HealthActivity for the request flow.
 */
object StepTracker {
    private const val TAG = "StepTracker"
    private const val PREFS_NAME = "ahead_step_tracker"
    private const val KEY_BASELINE = "baseline_raw_count"
    private const val KEY_BASELINE_DATE = "baseline_date"
    private const val KEY_LAST_RAW = "last_raw_count"
    private const val KEY_TODAY_STEPS = "today_steps"

    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null

    fun isAvailable(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)
            ?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /**
     * Safe to call repeatedly - a no-op if already registered, if the
     * device has no step-counter hardware, or if ACTIVITY_RECOGNITION isn't
     * granted (checked explicitly AND wrapped in try/catch below - this
     * runs from GlucoseStatusService.onCreate(), the persistent glucose
     * monitor, which must never crash because of an optional, unrelated
     * feature. registerListener() throws SecurityException without this
     * permission on API 29+, so both belt and suspenders here are load-
     * bearing, not defensive-programming theater).
     */
    fun start(context: Context) {
        if (listener != null) return
        val appContext = context.applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "ACTIVITY_RECOGNITION not granted - step tracking stays off until HealthActivity requests it")
            return
        }

        try {
            val sm = appContext.getSystemService(SensorManager::class.java) ?: return
            val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
            sensorManager = sm

            val newListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    onRawCount(appContext, event.values[0].toInt())
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            listener = newListener
            val registered = sm.registerListener(newListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "registerListener: $registered")
        } catch (e: SecurityException) {
            Log.w(TAG, "registerListener denied despite permission check passing - step tracking stays off", e)
            listener = null
            sensorManager = null
        }
    }

    fun stop() {
        val sm = sensorManager ?: return
        listener?.let { sm.unregisterListener(it) }
        listener = null
        sensorManager = null
    }

    private fun onRawCount(context: Context, rawCount: Int) {
        val prefs = prefs(context)
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        val baselineDate = prefs.getString(KEY_BASELINE_DATE, null)
        val lastRaw = prefs.getInt(KEY_LAST_RAW, -1)

        // A raw count LOWER than the last one we saw can only mean a device
        // reboot (the sensor's own counter resets to 0) - never a real "steps
        // went backward". Re-baseline to 0 so today's total keeps climbing
        // from where it actually is instead of going permanently negative.
        val rebooted = lastRaw >= 0 && rawCount < lastRaw

        val baseline = when {
            baselineDate != today -> rawCount // new day (or first-ever reading)
            rebooted -> 0
            else -> prefs.getInt(KEY_BASELINE, rawCount)
        }

        val todaySteps = (rawCount - baseline).coerceAtLeast(0)

        prefs.edit()
            .putInt(KEY_BASELINE, baseline)
            .putString(KEY_BASELINE_DATE, today)
            .putInt(KEY_LAST_RAW, rawCount)
            .putInt(KEY_TODAY_STEPS, todaySteps)
            .apply()
    }

    /** Last known steps-today total. Null means "never seen a sensor event
     *  yet" (service just started, permission not granted, or no step
     *  sensor on this device) - distinct from a real 0. If the persisted
     *  baseline is from a prior day (the service hasn't gotten a fresh
     *  event yet today), reports 0 rather than yesterday's leftover total. */
    fun todaySteps(context: Context): Int? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_TODAY_STEPS)) return null
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        if (prefs.getString(KEY_BASELINE_DATE, null) != today) return 0
        return prefs.getInt(KEY_TODAY_STEPS, 0)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
