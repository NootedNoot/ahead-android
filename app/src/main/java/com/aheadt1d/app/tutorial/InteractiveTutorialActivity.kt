package com.aheadt1d.app.tutorial

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.DashPathEffect
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aheadt1d.app.R
import com.aheadt1d.app.ui.GlucoseSeverity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.util.Locale
import kotlin.math.abs

class InteractiveTutorialActivity : AppCompatActivity() {

    private lateinit var chart: LineChart
    private lateinit var spinnerScenarios: Spinner
    private lateinit var tvStepIndicator: TextView
    private lateinit var tvGlucoseValue: TextView
    private lateinit var tvTrendArrow: TextView
    private lateinit var tvRateText: TextView
    private lateinit var tvProjectionText: TextView
    private lateinit var tvSeverityBadge: TextView
    private lateinit var simulatedYellowBanner: LinearLayout
    private lateinit var tvSimulatedBannerText: TextView

    private lateinit var tvCoachingHeader: TextView
    private lateinit var tvCoachingBody: TextView
    private lateinit var tvRuleOfThumb: TextView
    private lateinit var tvActionPrompt: TextView

    private lateinit var btnPrevStep: Button
    private lateinit var btnNextStep: Button

    private var currentScenarioIndex = 0
    private var currentStepIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_interactive_tutorial)

        findViewById<ImageButton>(R.id.btnTutorialBack).setOnClickListener { finish() }

        chart = findViewById(R.id.tutorialChart)
        spinnerScenarios = findViewById(R.id.spinnerScenarios)
        tvStepIndicator = findViewById(R.id.tvStepIndicator)
        tvGlucoseValue = findViewById(R.id.tvGlucoseValue)
        tvTrendArrow = findViewById(R.id.tvTrendArrow)
        tvRateText = findViewById(R.id.tvRateText)
        tvProjectionText = findViewById(R.id.tvProjectionText)
        tvSeverityBadge = findViewById(R.id.tvSeverityBadge)
        simulatedYellowBanner = findViewById(R.id.simulatedYellowBanner)
        tvSimulatedBannerText = findViewById(R.id.tvSimulatedBannerText)

        tvCoachingHeader = findViewById(R.id.tvCoachingHeader)
        tvCoachingBody = findViewById(R.id.tvCoachingBody)
        tvRuleOfThumb = findViewById(R.id.tvRuleOfThumb)
        tvActionPrompt = findViewById(R.id.tvActionPrompt)

        btnPrevStep = findViewById(R.id.btnPrevStep)
        btnNextStep = findViewById(R.id.btnNextStep)

        setupChart()
        setupSpinner()
        setupNavigation()

        renderCurrentStep()
    }

    private fun setupChart() {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        chart.axisRight.isEnabled = false
        chart.setBackgroundColor(Color.TRANSPARENT)

        val mutedColor = ContextCompat.getColor(this, R.color.muted)
        val borderColor = ContextCompat.getColor(this, R.color.border)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = borderColor
            textColor = mutedColor
            textSize = 9f
            setDrawAxisLine(false)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return if (value >= 0) "+${value.toInt()}m" else "${value.toInt()}m"
                }
            }
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = borderColor
            textColor = mutedColor
            textSize = 9f
            setDrawAxisLine(false)
            granularity = 25f
            isGranularityEnabled = true
            axisMinimum = 30f
            axisMaximum = 300f
        }
    }

    private fun setupSpinner() {
        val scenarioTitles = TutorialRepository.scenarios.map { "${it.badge}: ${it.title}" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, scenarioTitles)
        spinnerScenarios.adapter = adapter
        spinnerScenarios.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != currentScenarioIndex) {
                    currentScenarioIndex = position
                    currentStepIndex = 0
                    renderCurrentStep()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupNavigation() {
        btnPrevStep.setOnClickListener {
            if (currentStepIndex > 0) {
                currentStepIndex--
                renderCurrentStep()
            }
        }

        btnNextStep.setOnClickListener {
            val scenario = TutorialRepository.scenarios[currentScenarioIndex]
            if (currentStepIndex < scenario.steps.size - 1) {
                currentStepIndex++
                renderCurrentStep()
            } else {
                // Completed scenario
                if (currentScenarioIndex < TutorialRepository.scenarios.size - 1) {
                    currentScenarioIndex++
                    currentStepIndex = 0
                    spinnerScenarios.setSelection(currentScenarioIndex)
                } else {
                    finish()
                }
            }
        }
    }

    private fun renderCurrentStep() {
        val scenario = TutorialRepository.scenarios[currentScenarioIndex]
        val step = scenario.steps[currentStepIndex]

        tvStepIndicator.text = "Step ${currentStepIndex + 1} of ${scenario.steps.size}"

        // Update live card
        tvGlucoseValue.text = step.value.toString()
        val glucoseColor = ContextCompat.getColor(this, GlucoseSeverity.bucketFor(step.value).colorRes)
        tvGlucoseValue.setTextColor(glucoseColor)

        val deltaText = when {
            step.deltaFromPrevious == null -> ""
            step.deltaFromPrevious > 0 -> " (+${step.deltaFromPrevious} mg/dL)"
            step.deltaFromPrevious < 0 -> " (-${abs(step.deltaFromPrevious)} mg/dL)"
            else -> " (±0 mg/dL)"
        }
        val arrowSymbol = when {
            step.ratePerMinute <= -2.0 -> "⬇"
            step.ratePerMinute <= -1.2 -> "↘"
            step.ratePerMinute >= 2.0 -> "⬆"
            step.ratePerMinute >= 1.2 -> "↗"
            else -> "→"
        }
        tvTrendArrow.text = "$arrowSymbol$deltaText"

        val sign = if (step.ratePerMinute > 0) "+" else ""
        tvRateText.text = "Rate: $sign${"%.1f".format(Locale.US, step.ratePerMinute)} mg/dL/min"

        if (step.projected15m != null && step.projected30m != null) {
            tvProjectionText.text = " · Projected: ${step.projected15m} in 15m · ${step.projected30m} in 30m"
            tvProjectionText.visibility = View.VISIBLE
        } else if (step.projected15m != null) {
            tvProjectionText.text = " · Projected: ${step.projected15m} in 15m"
            tvProjectionText.visibility = View.VISIBLE
        } else {
            tvProjectionText.visibility = View.GONE
        }

        when (step.severity) {
            "yellow" -> {
                tvSeverityBadge.text = "YELLOW ALERT"
                tvSeverityBadge.setTextColor(ContextCompat.getColor(this, R.color.high))
                simulatedYellowBanner.visibility = View.VISIBLE
                val checkNum = when (step.readingIndex) {
                    1 -> 1
                    2 -> 2
                    else -> 3
                }
                tvSimulatedBannerText.text = when (checkNum) {
                    1 -> "⏳ Yellow Alert: Reading 1 of 3 (10 min left) — confirming trend."
                    2 -> "⏳ Yellow Alert: Reading 2 of 3 (5 min left) — observing rate."
                    else -> "⏳ Yellow Alert: Reading 3 of 3 (Trend confirmed) — curve leveled."
                }
            }
            "red" -> {
                tvSeverityBadge.text = "RED ALERT"
                tvSeverityBadge.setTextColor(ContextCompat.getColor(this, R.color.low))
                simulatedYellowBanner.visibility = View.GONE
            }
            else -> {
                tvSeverityBadge.text = "NORMAL"
                tvSeverityBadge.setTextColor(ContextCompat.getColor(this, R.color.ok))
                simulatedYellowBanner.visibility = View.GONE
            }
        }

        // Update Coaching Card
        tvCoachingHeader.text = step.coachingHeader
        tvCoachingBody.text = step.coachingBody
        tvRuleOfThumb.text = step.aheadRuleOfThumb
        tvActionPrompt.text = step.userActionPrompt

        // Update Navigation Buttons
        btnPrevStep.isEnabled = currentStepIndex > 0
        btnPrevStep.alpha = if (currentStepIndex > 0) 1.0f else 0.4f

        if (currentStepIndex < scenario.steps.size - 1) {
            btnNextStep.text = "Next Reading (+5m) ▶"
        } else {
            if (currentScenarioIndex < TutorialRepository.scenarios.size - 1) {
                btnNextStep.text = "Next Scenario ▶"
            } else {
                btnNextStep.text = "✓ Finish Tour"
            }
        }

        renderChart(scenario, currentStepIndex)
    }

    private fun renderChart(scenario: TutorialScenario, upToStepIndex: Int) {
        val historyEntries = mutableListOf<Entry>()
        val latestStep = scenario.steps[upToStepIndex]

        for (i in 0..upToStepIndex) {
            val s = scenario.steps[i]
            historyEntries.add(Entry(s.timeMinutes.toFloat(), s.value.toFloat()))
        }

        val primaryLine = LineDataSet(historyEntries, "Readings").apply {
            color = ContextCompat.getColor(this@InteractiveTutorialActivity, R.color.accent)
            lineWidth = 2.5f
            setDrawCircles(true)
            circleRadius = 4.5f
            setCircleColor(ContextCompat.getColor(this@InteractiveTutorialActivity, R.color.accent2))
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        val dataSets = mutableListOf<LineDataSet>(primaryLine)

        // Draw dashed projection line forward if available
        if (latestStep.projected15m != null && latestStep.projected30m != null) {
            val projEntries = listOf(
                Entry(latestStep.timeMinutes.toFloat(), latestStep.value.toFloat()),
                Entry((latestStep.timeMinutes + 15).toFloat(), latestStep.projected15m.toFloat()),
                Entry((latestStep.timeMinutes + 30).toFloat(), latestStep.projected30m.toFloat())
            )
            val projLine = LineDataSet(projEntries, "Projection").apply {
                color = when (latestStep.severity) {
                    "red" -> ContextCompat.getColor(this@InteractiveTutorialActivity, R.color.low)
                    "yellow" -> ContextCompat.getColor(this@InteractiveTutorialActivity, R.color.high)
                    else -> ContextCompat.getColor(this@InteractiveTutorialActivity, R.color.ok)
                }
                lineWidth = 2.0f
                enableDashedLine(10f, 6f, 0f)
                setDrawCircles(true)
                circleRadius = 3.0f
                setCircleColor(color)
                setDrawValues(false)
            }
            dataSets.add(projLine)
        }

        chart.data = LineData(dataSets.toList())
        val minX = 0f
        val maxX = (scenario.steps.maxOf { it.timeMinutes } + 35).toFloat()
        chart.xAxis.axisMinimum = minX
        chart.xAxis.axisMaximum = maxX
        chart.invalidate()
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, InteractiveTutorialActivity::class.java)
    }
}
