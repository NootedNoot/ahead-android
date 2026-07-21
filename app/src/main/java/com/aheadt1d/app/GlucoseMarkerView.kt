package com.aheadt1d.app

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Floating bubble shown above a tapped chart point - the glucose value and
 * its time. Entry.x is minutes elapsed since `anchor` (see MainActivity's
 * renderChart), not an index into some separate label list, so the marker
 * derives its own time text the same way the x-axis formatter does.
 */
class GlucoseMarkerView(
    context: Context,
    private val anchor: Instant,
    private val zone: ZoneId
) : MarkerView(context, R.layout.marker_view) {

    private val valueText: TextView = findViewById(R.id.markerValueText)
    private val timeText: TextView = findViewById(R.id.markerTimeText)

    override fun refreshContent(e: Entry, highlight: Highlight) {
        valueText.text = "${e.y.toInt()} mg/dL"
        val instant = anchor.plusSeconds((e.x * 60).toLong())
        timeText.text = TIME_FORMATTER.format(instant.atZone(zone))
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF = MPPointF(-(width / 2f), -height.toFloat() - 12f)

    companion object {
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")
    }
}
