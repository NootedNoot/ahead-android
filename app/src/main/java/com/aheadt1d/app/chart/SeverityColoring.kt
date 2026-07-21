package com.aheadt1d.app.chart

import android.graphics.Color

/**
 * Canonical sgv->color mapping, mirroring GlucoseSeverity.bucketFor's exact
 * thresholds/colors (app/src/main/java/com/aheadt1d/app/ui/GlucoseSeverity.kt).
 * Kept here as a hand-copied hex table rather than a GlucoseSeverity call so
 * this stays usable from contexts with no android.content.Context (the
 * report Canvas renderer, the interactive HTML/JSON export) - GraphActivity's
 * live chart also routes through this now instead of its own
 * ContextCompat.getColor call, so there's exactly one copy instead of two.
 * If GlucoseSeverity's palette or thresholds ever change, update this table
 * to match.
 */
object SeverityColoring {
    fun colorInt(sgv: Int): Int = when {
        sgv <= 60 -> Color.parseColor("#B0179E")  // glucose_severe_low
        sgv < 70 -> Color.parseColor("#E0A030")   // glucose_low
        sgv < 180 -> Color.parseColor("#3DDC97")  // glucose_normal
        sgv < 300 -> Color.parseColor("#F4B740")  // glucose_elevated
        sgv <= 400 -> Color.parseColor("#E8552C") // glucose_high
        else -> Color.parseColor("#8C1032")       // glucose_critical_high
    }

    /** Label + color pairs for a color-key legend, built from representative
     *  values through the exact same [colorInt] the chart line/band itself
     *  uses - so a legend can never drift out of sync with what's drawn. */
    val SEVERITY_LEGEND: List<Pair<String, Int>> = listOf(
        "Severe low" to colorInt(50),
        "Low" to colorInt(65),
        "In range" to colorInt(100),
        "Elevated" to colorInt(200),
        "High" to colorInt(350),
        "Critical high" to colorInt(450),
    )
}
