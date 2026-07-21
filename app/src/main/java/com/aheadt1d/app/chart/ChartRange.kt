package com.aheadt1d.app.chart

import com.aheadt1d.app.health.GlucosePoint
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor

/** An arbitrary [start, end] window - the one range shape shared by the live
 *  in-app chart, the doctor report, and the interactive export, replacing
 *  each screen's own "window minutes ending at now" or fixed-dates concept. */
data class ChartRange(val start: Instant, val end: Instant)

/**
 * Y-axis bound modes, moved here from GraphActivity's private copy so the
 * live chart and any future consumer share exactly one definition instead of
 * one per screen.
 */
enum class RangeMode {
    TIGHT, FULL, AUTO;

    /** TIGHT/FULL are fixed; AUTO fits to the given readings, padded and
     *  snapped to GRID_STEP so gridlines always land on clean numbers
     *  (50/75/100/125/150-style) instead of drifting to whatever the data's
     *  min/max happens to be - same reasoning as GraphActivity's original
     *  applyYAxisRange. */
    fun yBounds(readings: List<GlucosePoint>): Pair<Float, Float> = when (this) {
        TIGHT -> 70f to 180f
        FULL -> 40f to 400f
        AUTO -> {
            if (readings.isEmpty()) {
                40f to 400f
            } else {
                val lo = readings.minOf { it.sgv }.toFloat()
                val hi = readings.maxOf { it.sgv }.toFloat()
                val paddedLo = (lo - AUTO_RANGE_PADDING).coerceAtLeast(0f)
                val paddedHi = hi + AUTO_RANGE_PADDING
                (floor(paddedLo / GRID_STEP) * GRID_STEP) to (ceil(paddedHi / GRID_STEP) * GRID_STEP)
            }
        }
    }

    companion object {
        const val GRID_STEP = 25f
        const val AUTO_RANGE_PADDING = 20f
    }
}
