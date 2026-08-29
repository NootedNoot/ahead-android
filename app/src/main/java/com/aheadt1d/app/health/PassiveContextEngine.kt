package com.aheadt1d.app.health

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.aheadt1d.app.data.GlucoseVaultDatabase
import com.aheadt1d.app.notifications.GlucoseDisplayState
import java.time.LocalTime
import java.time.ZoneId
import org.aheadt1d.ratemath.HourlyStats
import org.aheadt1d.ratemath.PersonalAnomalyTier
import org.aheadt1d.ratemath.PersonalBaseline
import org.aheadt1d.ratemath.RatePoint

/**
 * On-Device Passive Context Engine for Ahead.
 *
 * Extracts rich physiological and situational context purely from ambient,
 * passive on-device streams - requiring ZERO manual logging from the user:
 *
 * 1. Dwell Duration & AUC Momentum: Identifies stubborn high plateaus vs sticky lows.
 * 2. Circadian Phase: Dawn phenomenon (04:00-08:00), nocturnal sleep (23:00-06:00), meal windows.
 * 3. Native Step Motion: Detects exercise-induced drops via StepTracker.
 * 4. Mathematical Curvature: Evaluates 2nd-derivative acceleration (rounding peaks vs crashes).
 */
object PassiveContextEngine {

    // Item 3 of the "smarter math" additions (2026-08-29): how unusual the
    // current reading is for THIS person specifically, at this hour -
    // never touches severity/AlertCoordinator (see PersonalBaseline's own
    // class doc on why that boundary matters), purely an extra insight
    // alongside the ones already generated below.
    //
    // Building the hourly baseline means grouping ~2 weeks of archive data
    // by hour-of-day - real work, not something to redo on every 20-second
    // UI refresh (evaluateContext runs on the main thread, called from
    // MainActivity's chart-refresh timer). Cached and only rebuilt once
    // this long after the last build; a stale-by-a-few-hours baseline is a
    // fine tradeoff for a purely informational insight, unlike anything
    // safety-relevant which never gets this kind of caching treatment
    // anywhere else in this app.
    private const val BASELINE_HISTORY_DAYS = 14L
    private const val BASELINE_CACHE_TTL_MS = 6 * 60 * 60_000L
    private var cachedBaseline: Map<Int, HourlyStats?>? = null
    private var cachedBaselineBuiltAtMs: Long = 0L

    /** Test-only: this object's baseline cache otherwise outlives any
     *  single test (it's a Kotlin `object`, one instance per test JVM/
     *  classloader) - call this between tests that need a fresh build
     *  against freshly-inserted vault data, not a stale cached one from
     *  an earlier test. */
    @VisibleForTesting
    fun resetBaselineCacheForTesting() {
        cachedBaseline = null
        cachedBaselineBuiltAtMs = 0L
    }

    private fun hourlyBaseline(context: Context): Map<Int, HourlyStats?> {
        val now = System.currentTimeMillis()
        cachedBaseline?.let { if (now - cachedBaselineBuiltAtMs < BASELINE_CACHE_TTL_MS) return it }

        val vault = GlucoseVaultDatabase.getInstance(context)
        val since = now - BASELINE_HISTORY_DAYS * 24 * 60 * 60_000L
        val points = vault.getReadingsBetween(since, now).map { RatePoint(it.epochMillis, it.sgv) }
        val baseline = PersonalBaseline.buildHourlyBaseline(points)

        cachedBaseline = baseline
        cachedBaselineBuiltAtMs = now
        return baseline
    }

    /** Null when there isn't enough personal history for this hour yet, or
     *  when the reading is perfectly typical - callers should treat null
     *  as "nothing to say," same as every other insight source here. */
    private fun personalAnomalyInsight(context: Context, reading: GlucoseDisplayState.Reading): Pair<String?, String?>? {
        val baseline = hourlyBaseline(context)
        val zScore = PersonalBaseline.zScore(reading.value, reading.readingTime, baseline)
        return when (PersonalBaseline.classify(zScore)) {
            PersonalAnomalyTier.HIGHLY_UNUSUAL ->
                "This is quite unusual for you at this time of day" to "Your own recent pattern at this hour usually looks different"
            PersonalAnomalyTier.SOMEWHAT_UNUSUAL ->
                "A bit outside your usual pattern for this time of day" to null
            else -> null
        }
    }

    enum class CircadianPhase {
        DAWN_SURGE,
        MEAL_WINDOW,
        DAYTIME_ACTIVE,
        EVENING_WIND_DOWN,
        NOCTURNAL_SLEEP
    }

    enum class CurvatureState {
        FLAT_PLATEAU,
        ROUNDING_PEAK,
        BOTTOMING_OUT,
        STEEP_ACCELERATION
    }

    data class ContextSummary(
        val circadianPhase: CircadianPhase,
        val dwellMinutesInBand: Long?,
        val isStubbornHigh: Boolean,
        val isStickyLow: Boolean,
        val isExerciseDrop: Boolean,
        val curvature: CurvatureState,
        val primaryInsight: String?,
        val actionableTip: String?
    )

    /**
     * Evaluates full passive context from the current reading, history, and on-device step sensors.
     */
    fun evaluateContext(
        context: Context,
        reading: GlucoseDisplayState.Reading,
        history: List<GlucosePoint>,
    ): ContextSummary {
        val now = LocalTime.now(ZoneId.systemDefault())
        val phase = classifyCircadian(now)
        val dwellMinutes = computeDwellMinutes(reading.value, history)
        
        val isStubbornHigh = reading.value >= 180 && (dwellMinutes ?: 0L) >= 45L
        val isStickyLow = reading.value <= 70 && (dwellMinutes ?: 0L) >= 20L

        val rate = reading.ratePerMinute ?: 0.0
        val curvature = classifyCurvature(history, rate)

        // Step tracker detection (recent exertion)
        val stepsToday = StepTracker.todaySteps(context) ?: 0
        val isExerciseDrop = reading.value <= 130 && rate <= -1.5 && stepsToday > 500

        // Synthesize human-readable proactive insight - the more specific/
        // actionable signals above all take priority; personal-baseline
        // unusualness is deliberately the lowest-priority fallback (a
        // softer, more general observation than "you're exercising" or
        // "this peak is rounding off").
        val (insight, tip) = generateInsight(
            value = reading.value,
            rate = rate,
            phase = phase,
            isStubbornHigh = isStubbornHigh,
            isStickyLow = isStickyLow,
            isExerciseDrop = isExerciseDrop,
            curvature = curvature,
            dwellMinutes = dwellMinutes
        ).let { primary ->
            if (primary.first != null) primary else personalAnomalyInsight(context, reading) ?: primary
        }

        return ContextSummary(
            circadianPhase = phase,
            dwellMinutesInBand = dwellMinutes,
            isStubbornHigh = isStubbornHigh,
            isStickyLow = isStickyLow,
            isExerciseDrop = isExerciseDrop,
            curvature = curvature,
            primaryInsight = insight,
            actionableTip = tip
        )
    }

    private fun classifyCircadian(time: LocalTime): CircadianPhase {
        val hour = time.hour
        val minute = time.minute
        val totalMinutes = hour * 60 + minute

        return when {
            totalMinutes in (4 * 60)..(7 * 60 + 30) -> CircadianPhase.DAWN_SURGE
            totalMinutes in (0..5 * 60 + 59) || totalMinutes >= (23 * 60) -> CircadianPhase.NOCTURNAL_SLEEP
            hour in 7..9 || hour in 11..13 || hour in 17..20 -> CircadianPhase.MEAL_WINDOW
            hour in 20..22 -> CircadianPhase.EVENING_WIND_DOWN
            else -> CircadianPhase.DAYTIME_ACTIVE
        }
    }

    private fun computeDwellMinutes(currentValue: Int, history: List<GlucosePoint>): Long? {
        if (history.isEmpty()) return null
        val sorted = history.sortedBy { it.time }
        val isHigh = currentValue >= 180
        val isLow = currentValue <= 70
        if (!isHigh && !isLow) return null

        var start = sorted.last().time
        for (i in sorted.lastIndex downTo 0) {
            val pt = sorted[i]
            val inBand = if (isHigh) pt.sgv >= 170 else pt.sgv <= 75
            if (!inBand) break
            start = pt.time
        }
        return java.time.Duration.between(start, sorted.last().time).toMinutes()
    }

    private fun classifyCurvature(history: List<GlucosePoint>, currentRate: Double): CurvatureState {
        if (history.size < 3) return CurvatureState.FLAT_PLATEAU
        val sorted = history.sortedBy { it.time }
        val p0 = sorted[sorted.size - 3]
        val p1 = sorted[sorted.size - 2]
        val p2 = sorted[sorted.size - 1]

        val m1 = (p1.time.toEpochMilli() - p0.time.toEpochMilli()) / 60_000.0
        val m2 = (p2.time.toEpochMilli() - p1.time.toEpochMilli()) / 60_000.0
        if (m1 <= 0 || m2 <= 0) return CurvatureState.FLAT_PLATEAU

        val r1 = (p1.sgv - p0.sgv) / m1
        val r2 = (p2.sgv - p1.sgv) / m2
        val acceleration = r2 - r1

        return when {
            currentRate > 1.0 && acceleration < -0.3 -> CurvatureState.ROUNDING_PEAK
            currentRate < -1.0 && acceleration > 0.3 -> CurvatureState.BOTTOMING_OUT
            kotlin.math.abs(currentRate) >= 2.5 -> CurvatureState.STEEP_ACCELERATION
            else -> CurvatureState.FLAT_PLATEAU
        }
    }

    private fun generateInsight(
        value: Int,
        rate: Double,
        phase: CircadianPhase,
        isStubbornHigh: Boolean,
        isStickyLow: Boolean,
        isExerciseDrop: Boolean,
        curvature: CurvatureState,
        dwellMinutes: Long?
    ): Pair<String?, String?> {
        return when {
            isExerciseDrop -> 
                "Active exertion pulling glucose down" to "Consider 10-15g fast fuel to buffer muscle uptake"
            isStickyLow -> 
                "Sticky low (${dwellMinutes ?: 20}m below 70)" to "Fast carbs active; give digestion time to absorb"
            isStubbornHigh -> 
                "Stubborn high (${dwellMinutes ?: 45}m over 180)" to "Insulin resistance or high-fat tail; hydration helps"
            curvature == CurvatureState.ROUNDING_PEAK -> 
                "Peak is rounding off" to "Rate easing toward flat; insulin is taking effect"
            curvature == CurvatureState.BOTTOMING_OUT -> 
                "Drop is bottoming out" to "Descent slowing; approaching stable baseline"
            phase == CircadianPhase.DAWN_SURGE && value >= 130 && rate > 0.5 -> 
                "Dawn cortisol surge active" to "Morning baseline rising naturally"
            phase == CircadianPhase.NOCTURNAL_SLEEP && rate <= -2.0 && value <= 70 -> 
                "Possible compression low" to "Check if laying directly on sensor site"
            else -> null to null
        }
    }
}
