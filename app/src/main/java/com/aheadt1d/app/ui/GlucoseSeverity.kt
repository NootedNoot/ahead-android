package com.aheadt1d.app.ui

import androidx.annotation.ColorRes
import com.aheadt1d.app.R

/**
 * SINGLE SOURCE OF TRUTH for how a glucose value is presented: colour, human
 * label, urgency border weight, and how the number should be drawn (coloured
 * text vs. a coloured fill behind a light number). Nothing else should map a
 * value to a colour - route every call site through here so the ladder can't
 * drift, the same way trend-detector.js owns the alert thresholds.
 *
 * This is the DISPLAY ladder (value-based), intentionally separate from the
 * backend's projection-based ALERT severity (none/yellow/red): a reading can
 * show "ELEVATED" here while the alert layer stays quiet because it's projected
 * back into range. Two different questions - "what is it right now" vs. "is it
 * heading somewhere dangerous."
 *
 * Accessibility (WCAG): colour never carries meaning alone. Every bucket also
 * has a text [label] and a shape-distinct [iconShape]; [borderWidthDp] scales
 * with urgency so shape/weight signal severity independent of hue.
 */
enum class GlucoseBucket(
    val label: String,
    @ColorRes val colorRes: Int,
    val borderWidthDp: Int,
    val iconShape: IconShape,
    /** True when [colorRes] is too dark to be legible as text on the near-black
     *  background and must instead be used as a FILL behind a light number. */
    val usesFill: Boolean,
) {
    SEVERE_LOW("SEVERE LOW", R.color.glucose_severe_low, 4, IconShape.DIAMOND, usesFill = true),
    LOW("LOW", R.color.glucose_low, 3, IconShape.CHEVRON_DOWN, usesFill = false),
    IN_RANGE("IN RANGE", R.color.glucose_normal, 1, IconShape.DOT, usesFill = false),
    ELEVATED("ELEVATED", R.color.glucose_elevated, 2, IconShape.CHEVRON_UP, usesFill = false),
    HIGH("HIGH", R.color.glucose_high, 3, IconShape.DOUBLE_CHEVRON_UP, usesFill = false),
    CRITICAL_HIGH("HIGH", R.color.glucose_critical_high, 4, IconShape.TRIANGLE, usesFill = true);

    /** The colour to draw the number in: the bucket colour when it's legible as
     *  text, otherwise a light colour that sits on the [colorRes] fill. */
    @get:ColorRes
    val numberColorRes: Int
        get() = if (usesFill) R.color.glucose_on_fill else colorRes
}

enum class IconShape { DOT, CHEVRON_UP, DOUBLE_CHEVRON_UP, TRIANGLE, CHEVRON_DOWN, DIAMOND }

object GlucoseSeverity {
    /** Ranges: <=floor severe-low · <70 low · 70-179 in-range · 180-299 elevated
     *  · 300-400 high · >400 critical-high.
     *
     *  The severe-low floor defaults to 60 (up from the color spec's 40) so the
     *  DISPLAY agrees with the backend's hard RED floor (SEVERE_LOW_RED_FLOOR):
     *  a reading like 57 that fires a RED alert now also renders in the severe
     *  (magenta) treatment instead of plain amber. Raised from the clinical 54
     *  cutoff so it fires before glucose is already deep in the hole. Still
     *  user-adjustable via the param. */
    fun bucketFor(sgv: Int, severeLowFloor: Int = 60): GlucoseBucket = when {
        sgv <= severeLowFloor -> GlucoseBucket.SEVERE_LOW
        sgv < 70 -> GlucoseBucket.LOW
        sgv < 180 -> GlucoseBucket.IN_RANGE
        sgv < 300 -> GlucoseBucket.ELEVATED
        sgv <= 400 -> GlucoseBucket.HIGH
        else -> GlucoseBucket.CRITICAL_HIGH
    }
}
