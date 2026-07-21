package com.aheadt1d.app.debug

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aheadt1d.app.R
import com.aheadt1d.app.alerts.PlateauMath
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.health.HealthConnectManager
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.state.RawReading
import com.aheadt1d.app.state.effectiveRatePerMinute
import com.aheadt1d.app.tuning.PlateauTuningParameters
import com.aheadt1d.app.tuning.PlateauTuningPrefs
import com.aheadt1d.app.tuning.TuningParameters
import com.aheadt1d.app.tuning.TuningPrefs
import com.aheadt1d.app.work.WorkScheduler
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Debug-only local what-if view. Save also sends the same values to the next
 * backend check, where server-side validation and real alert scoring occur. */
class TuningActivity : AppCompatActivity() {
    private lateinit var inputs: List<EditText>
    private lateinit var inputs2: List<EditText>
    private lateinit var liveReading: TextView
    private lateinit var liveRate: TextView
    private lateinit var liveProjected: TextView
    private lateinit var liveSeverity: TextView
    private lateinit var livePlateau: TextView

    // Refreshed on init/save/reset/timer only (a suspend Health Connect read),
    // not on every keystroke - renderPlateauPreview() below recomputes
    // duration/tier from this cache on every keystroke instead, which is
    // free (pure math), so the field-adjustment feedback loop stays instant
    // without hammering Health Connect once per digit typed.
    private var cachedPlateauPoints: List<GlucosePoint> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tuning)

        inputs = listOf(
            findViewById(R.id.yellowLowInput), findViewById(R.id.yellowHighInput),
            findViewById(R.id.redLowInput), findViewById(R.id.redHighInput),
            findViewById(R.id.extendedMinutesInput), findViewById(R.id.smoothingIntervalsInput),
        )
        inputs2 = listOf(
            findViewById(R.id.highThresholdInput), findViewById(R.id.highDurationInput),
            findViewById(R.id.hysteresisBufferInput), findViewById(R.id.escalationStepInput),
            findViewById(R.id.cooldownInput), findViewById(R.id.correctionWindowInput),
            findViewById(R.id.responseRateInput),
        )
        liveReading = findViewById(R.id.liveReadingText)
        liveRate = findViewById(R.id.liveRateText)
        liveProjected = findViewById(R.id.liveProjectedText)
        liveSeverity = findViewById(R.id.liveSeverityText)
        livePlateau = findViewById(R.id.livePlateauText)

        populate(TuningPrefs.load(this))
        populate2(PlateauTuningPrefs.load(this))
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                renderPreview()
                renderPlateauPreview()
            }
        }
        (inputs + inputs2).forEach { it.addTextChangedListener(watcher) }

        findViewById<Button>(R.id.saveTuningButton).setOnClickListener {
            val value = parseInputs()
            val value2 = parseInputs2()
            if (value == null || value2 == null) {
                Toast.makeText(this, "Enter valid numeric parameters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val normalized = value.normalized()
            val normalized2 = value2.normalized()
            TuningPrefs.save(this, normalized)
            PlateauTuningPrefs.save(this, normalized2)
            populate(normalized)
            populate2(normalized2)
            WorkScheduler.runOnce(applicationContext)
            lifecycleScope.launch { refreshPlateauPoints() }
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(currentFocus?.windowToken, 0)
            Toast.makeText(this, "Saved. A debug backend check was queued.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.resetTuningButton).setOnClickListener {
            TuningPrefs.reset(this)
            PlateauTuningPrefs.reset(this)
            populate(TuningPrefs.load(this))
            populate2(PlateauTuningPrefs.load(this))
            WorkScheduler.runOnce(applicationContext)
            lifecycleScope.launch { refreshPlateauPoints() }
            Toast.makeText(this, "Restored server defaults and queued a check.", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            LatestTrendRepository.latestRawReading.collect { renderPreview() }
        }
        lifecycleScope.launch {
            while (true) {
                refreshPlateauPoints()
                delay(PREVIEW_REFRESH_MS)
            }
        }
        lifecycleScope.launch {
            while (true) {
                renderPreview()
                delay(PREVIEW_REFRESH_MS)
            }
        }
    }

    private fun populate(value: TuningParameters) {
        val values = listOf(value.yellowProjectedLow, value.yellowProjectedHigh, value.redProjectedLow,
            value.redProjectedHigh, value.extendedProjectionMinutes, value.smoothingIntervals)
        inputs.zip(values).forEach { (input, number) -> input.setText(number.toString()) }
        renderPreview()
    }

    private fun parseInputs(): TuningParameters? {
        val values = inputs.map { it.text.toString().trim().toIntOrNull() ?: return null }
        return TuningParameters(
            yellowProjectedLow = values[0], yellowProjectedHigh = values[1],
            redProjectedLow = values[2], redProjectedHigh = values[3],
            extendedProjectionMinutes = values[4], smoothingIntervals = values[5],
        )
    }

    private fun populate2(value: PlateauTuningParameters) {
        val values = listOf(
            value.highThreshold.toString(), value.highDurationMinutes.toString(),
            value.hysteresisBuffer.toString(), value.escalationStepMinutes.toString(),
            value.cooldownMinutes.toString(), value.correctionWindowMinutes.toString(),
            formatRate(value.correctionResponseRateThreshold),
        )
        inputs2.zip(values).forEach { (input, text) -> input.setText(text) }
        renderPlateauPreview()
    }

    private fun parseInputs2(): PlateauTuningParameters? {
        val ints = inputs2.take(6).map { it.text.toString().trim().toIntOrNull() ?: return null }
        val rate = inputs2[6].text.toString().trim().toDoubleOrNull() ?: return null
        return PlateauTuningParameters(
            highThreshold = ints[0], highDurationMinutes = ints[1], hysteresisBuffer = ints[2],
            escalationStepMinutes = ints[3], cooldownMinutes = ints[4], correctionWindowMinutes = ints[5],
            correctionResponseRateThreshold = rate,
        )
    }

    /** Fetches (or, in debug builds, picks up injected/scenario data via
     *  HealthConnectManager's existing DebugGlucoseOverride substitution)
     *  the plateau lookback window and caches it, then renders. The only
     *  suspend/I-O path in the plateau preview - everything else recomputes
     *  from this cache. */
    private suspend fun refreshPlateauPoints() {
        val params = parseInputs2() ?: PlateauTuningPrefs.load(this)
        cachedPlateauPoints = HealthConnectManager.readGlucosePoints(this, params.lookbackMinutes())
        renderPlateauPreview()
    }

    /** Pure - never touches Health Connect, only PlateauMath over
     *  cachedPlateauPoints - safe to call on every keystroke. Read-only: no
     *  PlateauCoordinator call here, so this preview can never fire a real
     *  notification, mirroring how previewBucket() below never touches
     *  AlertCoordinator. */
    private fun renderPlateauPreview() {
        val params = parseInputs2()
        if (params == null) {
            livePlateau.text = "Plateau preview: enter valid parameters"
            livePlateau.setTextColor(ContextCompat.getColor(this, R.color.muted))
            return
        }
        if (cachedPlateauPoints.isEmpty()) {
            livePlateau.text = "Plateau preview: waiting for Health Connect"
            livePlateau.setTextColor(ContextCompat.getColor(this, R.color.muted))
            return
        }
        val duration = PlateauMath.currentPlateauDurationMinutes(cachedPlateauPoints, params.highThreshold)
        if (duration == null) {
            livePlateau.text = "Plateau preview: below ${params.highThreshold} mg/dL - no active plateau"
            livePlateau.setTextColor(ContextCompat.getColor(this, R.color.ok))
            return
        }
        val tier = PlateauMath.tierFor(duration, params.highDurationMinutes.toLong(), params.escalationStepMinutes.toLong())
        livePlateau.text = if (tier == 0) {
            "Plateau preview: ${duration}m at/above ${params.highThreshold} mg/dL - not yet at ${params.highDurationMinutes}m"
        } else {
            "Plateau preview: ${duration}m at/above ${params.highThreshold} mg/dL - tier $tier"
        }
        livePlateau.setTextColor(ContextCompat.getColor(this, if (tier == 0) R.color.muted else R.color.high))
    }

    private fun renderPreview() {
        val raw = LatestTrendRepository.latestRawReading.value
        val params = parseInputs() ?: TuningPrefs.load(this)
        if (raw == null) {
            liveReading.text = "Current glucose: waiting for Health Connect"
            liveRate.text = "Current rate: —"
            liveProjected.text = "Projected: —"
            liveSeverity.text = "Preview bucket: —"
            return
        }
        val rate = effectiveRatePerMinute(raw, LatestTrendRepository.latestTrend.value)
        val ageMinutes = ((System.currentTimeMillis() - raw.time) / 60_000).coerceAtLeast(0)
        liveReading.text = "Current glucose: ${raw.value} mg/dL (${ageMinutes}m ago)"
        liveRate.text = rate?.let { "Current reactive rate: ${formatRate(it)}/min" } ?: "Current reactive rate: —"
        if (rate == null) {
            liveProjected.text = "Projected (${params.extendedProjectionMinutes}m): —"
            liveSeverity.text = "Preview bucket: insufficient rate data"
            liveSeverity.setTextColor(ContextCompat.getColor(this, R.color.muted))
            return
        }
        val projected15 = (raw.value + rate * 15).roundToInt()
        val projectedExtended = (raw.value + rate * params.extendedProjectionMinutes).roundToInt()
        val bucket = previewBucket(raw, rate, projected15, projectedExtended, params)
        liveProjected.text = "Projected: $projected15 in 15m · $projectedExtended in ${params.extendedProjectionMinutes}m"
        liveSeverity.text = "Preview bucket: ${bucket.label}"
        liveSeverity.setTextColor(ContextCompat.getColor(this, bucket.colorRes))
    }

    private fun previewBucket(raw: RawReading, rate: Double, projected: Int, extended: Int, p: TuningParameters): Bucket {
        val red = projected <= p.redProjectedLow || projected >= p.redProjectedHigh ||
            (raw.value <= p.redProjectedLow && rate < 0) || (raw.value >= p.redProjectedHigh && rate > 0)
        if (red) return Bucket("RED", R.color.low)
        // Rate check mirrors trend-detector.js's classifySeverity - a fast rate
        // escalates to yellow on its own, independent of projection.
        val yellow = projected <= p.yellowProjectedLow || projected >= p.yellowProjectedHigh ||
            extended <= p.redProjectedLow || extended >= p.redProjectedHigh ||
            rate <= YELLOW_RATE_FALLING || rate >= YELLOW_RATE_RISING
        return if (yellow) Bucket("YELLOW", R.color.high) else Bucket("none", R.color.ok)
    }

    private fun formatRate(rate: Double): String = if (rate >= 0) "+%.1f".format(rate) else "%.1f".format(rate)

    private data class Bucket(val label: String, val colorRes: Int)
    private companion object {
        const val PREVIEW_REFRESH_MS = 5 * 60_000L
        // Mirrors trend-detector.js's YELLOW_RATE_FALLING/RISING defaults - kept
        // in sync by hand, same as the projection thresholds above.
        const val YELLOW_RATE_FALLING = -1.5
        const val YELLOW_RATE_RISING = 2.5
    }
}
