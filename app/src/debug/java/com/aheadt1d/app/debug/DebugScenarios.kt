package com.aheadt1d.app.debug

import com.aheadt1d.app.health.GlucosePoint
import java.time.Duration
import java.time.Instant
import kotlin.math.sin
import kotlin.random.Random

import org.aheadt1d.ratemath.CauseTier

/**
 * Preset glucose scenarios for the debug menu's chart/notification testing.
 * Each generator returns points spaced 5 minutes apart (matching a typical
 * CGM cadence) ending at `now`, so DebugGlucoseOverride.setPoints() + the
 * last point's rate can drive both the chart and the live alert chain.
 */
enum class DebugScenario(
    val label: String,
    val isDemo: Boolean = false,
    val demoDescription: String? = null
) {
    SLOW_RISE("Slow rise"),
    FAST_DROP("Fast drop"),
    POST_MEAL_SPIKE("Post-meal spike"),
    TODAYS_ACTUAL_SWING("Today's actual swing (82→319)"),
    FLATLINE_STALE("Flatline / stale sensor"),
    SUSTAINED_HIGH_PLATEAU("Sustained high plateau (flat 320, 3h)"),

    // ==================== CANONICAL CLINICAL DEMO SCENARIOS ====================
    DEMO_PREDICTIVE_HYPO_CATCH(
        label = "★ DEMO: Predictive Hypo Catch (125→62 mg/dL)",
        isDemo = true,
        demoDescription = "Ahead alerts at 96 mg/dL (-2.2/m) with an 18-min early warning (proj 56 mg/dL) while Dexcom is still silent."
    ),
    DEMO_TREATED_RECOVERY_SMART_MUTE(
        label = "★ DEMO: Treatment Recovery Smart Mute (65→118 mg/dL)",
        isDemo = true,
        demoDescription = "Patient consumes fast carbs; Ahead applies TREATED tier to suppress alarm fatigue while tracking recovery."
    ),
    DEMO_UNEXPLAINED_FALSE_REBOUND(
        label = "★ DEMO: Unexplained False Rebound (75→88→68 mg/dL)",
        isDemo = true,
        demoDescription = "Replays uncorrected bounce; UNEXPLAINED tier holds alert state across bounce so patient is not misled."
    ),
    DEMO_POST_MEAL_INSULIN_DECAY(
        label = "★ DEMO: Post-Meal Spike & Decay (130→230 mg/dL)",
        isDemo = true,
        demoDescription = "Post-meal spike rolls over as bolus acts; prevents panic rage-bolusing by projecting safe stabilization."
    ),
    DEMO_DELAYED_EXERCISE_RISK(
        label = "★ DEMO: Delayed Exercise Hypo (135→72 mg/dL)",
        isDemo = true,
        demoDescription = "Delayed nocturnal drop highlighting post-workout insulin sensitivity window."
    );

    /** Total span the scenario plays out over, used to scale playback timing. */
    fun durationMinutes(): Long = when (this) {
        SLOW_RISE -> 180L
        FAST_DROP -> 40L
        POST_MEAL_SPIKE -> 180L
        TODAYS_ACTUAL_SWING -> 180L
        FLATLINE_STALE -> 60L
        SUSTAINED_HIGH_PLATEAU -> 180L
        DEMO_PREDICTIVE_HYPO_CATCH -> 40L
        DEMO_TREATED_RECOVERY_SMART_MUTE -> 35L
        DEMO_UNEXPLAINED_FALSE_REBOUND -> 40L
        DEMO_POST_MEAL_INSULIN_DECAY -> 55L
        DEMO_DELAYED_EXERCISE_RISK -> 45L
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
            DEMO_PREDICTIVE_HYPO_CATCH -> listOf(125, 120, 114, 105, 96, 88, 78, 70, 62)
            DEMO_TREATED_RECOVERY_SMART_MUTE -> listOf(65, 66, 70, 78, 88, 98, 108, 118)
            DEMO_UNEXPLAINED_FALSE_REBOUND -> listOf(75, 76, 82, 88, 86, 80, 72, 68)
            DEMO_POST_MEAL_INSULIN_DECAY -> listOf(130, 150, 175, 205, 225, 230, 222, 205, 185, 160, 140, 130)
            DEMO_DELAYED_EXERCISE_RISK -> listOf(135, 130, 122, 110, 98, 86, 78, 72, 68)
        }
    }

    fun causeTierForPoint(index: Int, total: Int): CauseTier? = when (this) {
        DEMO_TREATED_RECOVERY_SMART_MUTE -> CauseTier.TREATED
        DEMO_UNEXPLAINED_FALSE_REBOUND -> CauseTier.UNEXPLAINED
        DEMO_DELAYED_EXERCISE_RISK -> CauseTier.EXERCISE_RISK
        else -> null
    }

    fun customRateForPoint(index: Int, total: Int): Double? = when (this) {
        DEMO_PREDICTIVE_HYPO_CATCH -> if (index >= 3) -2.2 else -1.2
        DEMO_TREATED_RECOVERY_SMART_MUTE -> 1.8
        DEMO_UNEXPLAINED_FALSE_REBOUND -> if (index < 4) 1.5 else -1.8
        DEMO_POST_MEAL_INSULIN_DECAY -> if (index < 5) 2.4 else -1.5
        DEMO_DELAYED_EXERCISE_RISK -> -1.4
        else -> null
    }

    fun customSeverityForPoint(index: Int, total: Int, sgv: Int): String? = when (this) {
        DEMO_PREDICTIVE_HYPO_CATCH -> {
            if (sgv <= 70) "red"
            else if (index >= 3) "yellow"
            else "none"
        }
        DEMO_TREATED_RECOVERY_SMART_MUTE -> {
            if (sgv <= 70) "red"
            else if (sgv < 90) "yellow"
            else "none"
        }
        DEMO_UNEXPLAINED_FALSE_REBOUND -> {
            if (sgv <= 70) "red"
            else "yellow"
        }
        DEMO_POST_MEAL_INSULIN_DECAY -> {
            if (sgv >= 250) "red"
            else if (sgv >= 180) "yellow"
            else "none"
        }
        DEMO_DELAYED_EXERCISE_RISK -> {
            if (sgv <= 70) "red"
            else if (sgv <= 90) "yellow"
            else "none"
        }
        else -> null
    }

    fun customProjectedForPoint(index: Int, total: Int, sgv: Int, rate: Double): Pair<Int?, Int?>? = when (this) {
        DEMO_PREDICTIVE_HYPO_CATCH -> {
            val p18 = (sgv + (rate * 18)).toInt().coerceIn(30, 400)
            val p30 = (sgv + (rate * 30)).toInt().coerceIn(30, 400)
            p18 to p30
        }
        DEMO_TREATED_RECOVERY_SMART_MUTE -> {
            val p18 = (sgv + (rate * 18)).toInt().coerceIn(30, 400)
            val p30 = (sgv + (rate * 30)).toInt().coerceIn(30, 400)
            p18 to p30
        }
        DEMO_UNEXPLAINED_FALSE_REBOUND -> {
            val p18 = (sgv + (rate * 18)).toInt().coerceIn(30, 400)
            val p30 = (sgv + (rate * 30)).toInt().coerceIn(30, 400)
            p18 to p30
        }
        DEMO_POST_MEAL_INSULIN_DECAY -> {
            val p18 = if (index < 5) 235 else 190
            val p30 = if (index < 5) 240 else 170
            p18 to p30
        }
        DEMO_DELAYED_EXERCISE_RISK -> {
            val p18 = (sgv + (rate * 18)).toInt().coerceIn(30, 400)
            val p30 = (sgv + (rate * 30)).toInt().coerceIn(30, 400)
            p18 to p30
        }
        else -> null
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
