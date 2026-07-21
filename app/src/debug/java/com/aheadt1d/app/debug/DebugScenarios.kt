package com.aheadt1d.app.debug

import com.aheadt1d.app.health.GlucosePoint
import java.time.Duration
import java.time.Instant
import kotlin.math.sin
import kotlin.random.Random

/**
 * Preset glucose scenarios for the debug menu's chart/notification testing.
 * Each generator returns points spaced 5 minutes apart (matching a typical
 * CGM cadence) ending at `now`, so DebugGlucoseOverride.setPoints() + the
 * last point's rate can drive both the chart and the live alert chain.
 */
enum class DebugScenario(val label: String) {
    SLOW_RISE("Slow rise"),
    FAST_DROP("Fast drop"),
    POST_MEAL_SPIKE("Post-meal spike"),
    TODAYS_ACTUAL_SWING("Today's actual swing (82→319)"),
    FLATLINE_STALE("Flatline / stale sensor"),
    SUSTAINED_HIGH_PLATEAU("Sustained high plateau (flat 320, 3h)");

    /** Total span the scenario plays out over, used to scale playback timing. */
    fun durationMinutes(): Long = when (this) {
        SLOW_RISE -> 180L
        FAST_DROP -> 40L
        POST_MEAL_SPIKE -> 180L
        TODAYS_ACTUAL_SWING -> 180L
        FLATLINE_STALE -> 60L
        SUSTAINED_HIGH_PLATEAU -> 180L
    }

    /** Values only, oldest to newest, 5 minutes apart - the caller stamps times. */
    fun values(): List<Int> {
        val steps = (durationMinutes() / STEP_MINUTES).toInt()
        return when (this) {
            SLOW_RISE -> (0..steps).map { i ->
                val t = i.toDouble() / steps
                (100 + t * 120).toInt() // 100 -> 220
            }
            FAST_DROP -> (0..steps).map { i ->
                val t = i.toDouble() / steps
                (180 - t * 120).toInt() // 180 -> 60
            }
            POST_MEAL_SPIKE -> (0..steps).map { i ->
                val t = i.toDouble() / steps
                // Rises fast for the first third, peaks, decays back down -
                // sin-shaped so the peak is smooth rather than a sharp corner.
                val phase = (t * Math.PI).coerceAtMost(Math.PI)
                (100 + sin(phase) * 140).toInt() // 100 -> ~240 -> 100
            }
            TODAYS_ACTUAL_SWING -> (0..steps).map { i ->
                val t = i.toDouble() / steps
                (82 + t * (319 - 82)).toInt()
            }
            FLATLINE_STALE -> (0..steps).map { 110 }
            // Flat well above the 250 mg/dL plateau default, held for the
            // full 3h span, with a touch of jitter so it isn't a perfectly
            // dead line - real sensor noise, not a slope PlateauMath should
            // ever mistake for "trending down."
            SUSTAINED_HIGH_PLATEAU -> (0..steps).map { i -> 320 + ((i * 7) % 5) - 2 }
        }
    }

    /** Full [GlucosePoint] series ending at `now`. */
    fun points(now: Instant = Instant.now()): List<GlucosePoint> {
        val values = values()
        val start = now.minus(Duration.ofMinutes((values.size - 1) * STEP_MINUTES))
        return values.mapIndexed { i, sgv ->
            GlucosePoint(time = start.plus(Duration.ofMinutes(i * STEP_MINUTES)), sgv = sgv)
        }
    }

    companion object {
        const val STEP_MINUTES = 5L
    }
}

/**
 * 14 days of 5-min-spaced points ending at `now`, with a deliberate 6-day gap
 * in the middle (no points at all, not just flat/stale ones) and a value
 * curve that cycles through every GlucoseSeverity zone - built specifically
 * to test the doctor report exporter end-to-end (full-range pagination,
 * gap-as-visible-break rendering, color coding across all zones, multi-day
 * x-axis/marker placement) without depending on whatever happens to be in
 * Health Connect on a given test device.
 */
fun twoWeekReportTestPoints(now: Instant = Instant.now()): List<GlucosePoint> {
    val totalStart = now.minus(Duration.ofDays(14))
    val gapStart = now.minus(Duration.ofDays(9))
    val gapEnd = now.minus(Duration.ofDays(3))
    val stepMinutes = 5L
    val points = mutableListOf<GlucosePoint>()
    var t = totalStart
    var i = 0
    while (!t.isAfter(now)) {
        if (t.isBefore(gapStart) || t.isAfter(gapEnd)) {
            // One full sine cycle per simulated day, amplitude wide enough to
            // sweep from severe-low through critical-high so every legend
            // color actually appears in the rendered line/band.
            val phase = (i % (288)) / 288.0 * 2 * Math.PI
            val value = (220 + sin(phase) * 200).toInt().coerceIn(20, 420)
            points.add(GlucosePoint(t, value))
        }
        t = t.plus(Duration.ofMinutes(stepMinutes))
        i++
    }
    return points
}

/** N noisy points across the last [windowMinutes], for chart-rendering stress tests. */
fun randomGlucosePoints(count: Int, windowMinutes: Long, now: Instant = Instant.now()): List<GlucosePoint> {
    if (count <= 0) return emptyList()
    val random = Random.Default
    var value = 90 + random.nextInt(120) // random starting point 90-210
    val start = now.minus(Duration.ofMinutes(windowMinutes))
    val stepMillis = Duration.ofMinutes(windowMinutes).toMillis() / count.coerceAtLeast(1)
    return (0 until count).map { i ->
        // Random walk with occasional larger jumps, clamped to a plausible CGM range.
        val jump = random.nextInt(-25, 26)
        value = (value + jump).coerceIn(40, 400)
        GlucosePoint(time = start.plusMillis(i * stepMillis), sgv = value)
    }
}
