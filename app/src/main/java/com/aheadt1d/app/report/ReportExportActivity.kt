package com.aheadt1d.app.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.R
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Doctor report export: pick a date range, generate the clinical + annotated
 * PDFs from the exact same underlying data (ReportDataAggregator), then hand
 * both to a share sheet. No existing date-range picker component to reuse in
 * this codebase (GraphActivity only has fixed 1h/3h/6h windows) - built fresh
 * here with three quick presets (14/30/90 days, the common AGP lookbacks)
 * plus MaterialDatePicker's date-range picker for anything else.
 */
class ReportExportActivity : AppCompatActivity() {

    private lateinit var preset14dButton: Button
    private lateinit var preset30dButton: Button
    private lateinit var preset90dButton: Button
    private lateinit var customRangeButton: Button
    private lateinit var selectedRangeText: TextView
    private lateinit var reportStatusText: TextView
    private lateinit var generateReportButton: Button
    private lateinit var generateInteractiveButton: Button

    private var rangeStart: Instant = Instant.now().minusSeconds(14L * 24 * 3600)
    private var rangeEnd: Instant = Instant.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_export)

        findViewById<android.widget.ImageButton>(R.id.reportBackButton).setOnClickListener { finish() }

        preset14dButton = findViewById(R.id.preset14dButton)
        preset30dButton = findViewById(R.id.preset30dButton)
        preset90dButton = findViewById(R.id.preset90dButton)
        customRangeButton = findViewById(R.id.customRangeButton)
        selectedRangeText = findViewById(R.id.selectedRangeText)
        reportStatusText = findViewById(R.id.reportStatusText)
        generateReportButton = findViewById(R.id.generateReportButton)
        generateInteractiveButton = findViewById(R.id.generateInteractiveButton)

        preset14dButton.setOnClickListener { selectPresetDays(14) }
        preset30dButton.setOnClickListener { selectPresetDays(30) }
        preset90dButton.setOnClickListener { selectPresetDays(90) }
        customRangeButton.setOnClickListener { openCustomRangePicker() }
        generateReportButton.setOnClickListener { generateAndShare() }
        generateInteractiveButton.setOnClickListener { generateInteractiveAndShare() }

        selectPresetDays(14)
    }

    private fun selectPresetDays(days: Int) {
        rangeEnd = Instant.now()
        rangeStart = rangeEnd.minusSeconds(days.toLong() * 24 * 3600)
        updatePresetStyles(active = when (days) {
            14 -> preset14dButton
            30 -> preset30dButton
            else -> preset90dButton
        })
        updateRangeText()
    }

    private fun updatePresetStyles(active: Button) {
        listOf(preset14dButton, preset30dButton, preset90dButton, customRangeButton).forEach { button ->
            val isActive = button === active
            button.setBackgroundResource(if (isActive) R.drawable.time_btn_active else R.drawable.time_btn_inactive)
            button.setTextColor(ContextCompat.getColor(this, if (isActive) R.color.accent2 else R.color.muted))
        }
    }

    private fun openCustomRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.doctor_report_range_label))
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val startMillis = selection.first
            val endMillis = selection.second
            rangeStart = Instant.ofEpochMilli(startMillis)
            // MaterialDatePicker's range is UTC-midnight-to-UTC-midnight on the
            // selected days - push the end to the end of that day so the last
            // selected day's readings are actually included, not cut off at 00:00.
            rangeEnd = Instant.ofEpochMilli(endMillis).plusSeconds(24 * 3600 - 1)
            updatePresetStyles(active = customRangeButton)
            updateRangeText()
        }
        picker.show(supportFragmentManager, "report_date_range")
    }

    private fun updateRangeText() {
        val zone = ZoneId.systemDefault()
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(zone)
        selectedRangeText.text = "${formatter.format(rangeStart)} – ${formatter.format(rangeEnd)}"
    }

    private fun generateAndShare() {
        setButtonsEnabled(false)
        reportStatusText.setText(R.string.generating_report)

        lifecycleScope.launch {
            try {
                val data = ReportDataAggregator.aggregate(applicationContext, rangeStart, rangeEnd)
                val reports = DoctorReportPdfGenerator.generate(applicationContext, data)
                shareReports(reports)
                reportStatusText.text = ""
            } catch (e: Exception) {
                reportStatusText.setText(R.string.report_generation_failed)
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun generateInteractiveAndShare() {
        setButtonsEnabled(false)
        reportStatusText.setText(R.string.generating_report)

        lifecycleScope.launch {
            try {
                val data = ReportDataAggregator.aggregate(applicationContext, rangeStart, rangeEnd)
                val file = InteractiveReportGenerator.generate(applicationContext, data)
                shareHtml(file)
                reportStatusText.text = ""
            } catch (e: Exception) {
                reportStatusText.setText(R.string.report_generation_failed)
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        generateReportButton.isEnabled = enabled
        generateInteractiveButton.isEnabled = enabled
    }

    private fun shareHtml(file: java.io.File) {
        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val uri = FileProvider.getUriForFile(this, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Ahead glucose report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.doctor_report_title)))
    }

    private fun shareReports(reports: DoctorReportPdfGenerator.GeneratedReports) {
        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val clinicalUri = FileProvider.getUriForFile(this, authority, reports.clinicalFile)
        val annotatedUri = FileProvider.getUriForFile(this, authority, reports.annotatedFile)

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(clinicalUri, annotatedUri))
            putExtra(Intent.EXTRA_SUBJECT, "Ahead glucose report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Both PDFs generated - the doctor conversation dictates which one
            // actually gets sent; most share targets let the user drop one
            // attachment before sending. clipData (not just the extra) is what
            // actually propagates the read-permission grant to every item for
            // an ACTION_SEND_MULTIPLE, not just the first.
            clipData = ClipData.newRawUri("", clinicalUri).apply {
                addItem(ClipData.Item(annotatedUri))
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.doctor_report_title)))
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, ReportExportActivity::class.java)
    }
}
