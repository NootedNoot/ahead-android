package com.aheadt1d.app.health

import com.aheadt1d.app.notifications.GlucoseDisplayState
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import java.time.Instant

/**
 * Programmatic Physiological Scenario Generator for Ahead Autopilot Testing.
 *
 * Generates synthetic, mathematically precise CGM trajectories to stress-test
 * rate calculations, multi-step decay projections, and alert state machines
 * without requiring live hardware.
 */
object ScenarioGenerator {

    data class ScenarioPoint(
        val time: Instant,
        val sgv: Int,
        val ratePerMinute: Double?,
        val projected15: Int?,
    )

    /**
     * Generates a rapid plunge into a severe low (exercise crash or overdose).
     * Starts at [startMgDl] and drops at [dropRatePerMin] for [durationMinutes].
     */
    fun createPlungeScenario(
        startMgDl: Int = 160,
        dropRatePerMin: Double = -3.0,
        durationMinutes: Int = 30,
        intervalMinutes: Int = 5,
    ): List<GlucosePoint> {
        val points = mutableListOf<GlucosePoint>()
        val startTime = Instant.now().minusSeconds(durationMinutes * 60L)
        val steps = durationMinutes / intervalMinutes

        for (i in 0..steps) {
            val elapsedMinutes = i * intervalMinutes
            val currentSgv = (startMgDl + (dropRatePerMin * elapsedMinutes)).toInt().coerceAtLeast(30)
            points.add(GlucosePoint(startTime.plusSeconds(elapsedMinutes * 60L), currentSgv))
        }
        return points
    }

    /**
     * Generates a postprandial meal spike that levels out and decelerates (pizza/carb tail).
     */
    fun createDeceleratingSpikeScenario(
        startMgDl: Int = 120,
        peakMgDl: Int = 230,
        durationMinutes: Int = 45,
    ): List<GlucosePoint> {
        val points = mutableListOf<GlucosePoint>()
        val startTime = Instant.now().minusSeconds(durationMinutes * 60L)
        
        // Modeled as an exponential approach to peak: G(t) = peak - (peak - start) * e^(-k*t)
        val k = 0.05
        for (m in 0..durationMinutes step 5) {
            val sgv = (peakMgDl - (peakMgDl - startMgDl) * Math.exp(-k * m)).toInt()
            points.add(GlucosePoint(startTime.plusSeconds(m * 60L), sgv))
        }
        return points
    }

    /**
     * Generates a treated low with active reversal and recovery (juice absorption).
     */
    fun createTreatedLowRecoveryScenario(
        lowMgDl: Int = 50,
        recoveryFloorMgDl: Int = 85,
        durationMinutes: Int = 30,
    ): List<GlucosePoint> {
        val points = mutableListOf<GlucosePoint>()
        val startTime = Instant.now().minusSeconds(durationMinutes * 60L)
        val riseRate = (recoveryFloorMgDl - lowMgDl).toDouble() / durationMinutes

        for (m in 0..durationMinutes step 5) {
            val sgv = (lowMgDl + (riseRate * m)).toInt()
            points.add(GlucosePoint(startTime.plusSeconds(m * 60L), sgv))
        }
        return points
    }

    /**
     * Generates an overnight sensor compression V-notch glitch (rolling onto sensor).
     */
    fun createCompressionGlitchScenario(
        baselineMgDl: Int = 115,
        dipMgDl: Int = 58,
    ): List<GlucosePoint> {
        val now = Instant.now()
        return listOf(
            GlucosePoint(now.minusSeconds(15 * 60L), baselineMgDl),
            GlucosePoint(now.minusSeconds(10 * 60L), baselineMgDl - 2),
            GlucosePoint(now.minusSeconds(5 * 60L), dipMgDl), // Instant compression drop
            GlucosePoint(now, baselineMgDl + 1)               // Instant bounce back
        )
    }

    /**
     * Generates a fluctuating treated high (insulin active, managing between 300-340).
     */
    fun createFluctuatingHighScenario(): List<GlucosePoint> {
        val now = Instant.now()
        val values = listOf(330, 315, 302, 328, 340, 312, 295)
        val points = mutableListOf<GlucosePoint>()
        for (i in values.indices) {
            val offsetMin = (values.size - 1 - i) * 5L
            points.add(GlucosePoint(now.minusSeconds(offsetMin * 60L), values[i]))
        }
        return points
    }
}
