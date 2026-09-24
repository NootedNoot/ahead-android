package com.aheadt1d.app.debug

import org.aheadt1d.ratemath.CauseTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the canonical clinical demo scenarios designed for endocrinologist
 * and beta-tester live demonstrations.
 *
 * Verifies that:
 * 1. All demo scenarios produce valid, non-empty, physiologically realistic series.
 * 2. Predictive Hypo Catch fires early while current SGV is still within normal range (>90 mg/dL).
 * 3. Treatment Recovery Smart Muting flags [CauseTier.TREATED] with a rising trajectory.
 * 4. Unexplained False Rebound holds [CauseTier.UNEXPLAINED] across the transient bounce.
 * 5. Post-Meal Spike & Insulin Decay demonstrates flattening/stabilization.
 * 6. Delayed Exercise Hypo indicates [CauseTier.EXERCISE_RISK] during nocturnal drop.
 */
class ClinicalDemoScenariosTest {

    @Test
    fun `all canonical demo scenarios have valid points and descriptions`() {
        val demoScenarios = DebugScenario.values().filter { it.isDemo }
        assertEquals(5, demoScenarios.size)

        for (scenario in demoScenarios) {
            assertTrue(scenario.label.startsWith("★ DEMO:"))
            assertNotNull(scenario.demoDescription)
            assertTrue("Description must not be blank", scenario.demoDescription!!.isNotBlank())

            val points = scenario.points()
            assertTrue("Scenario ${scenario.name} must have at least 5 points", points.size >= 5)

            // Ensure values are within valid human physiological bounds
            for (pt in points) {
                assertTrue("SGV out of range: ${pt.sgv}", pt.sgv in 30..500)
            }
        }
    }

    @Test
    fun `DEMO_PREDICTIVE_HYPO_CATCH catches rapid drop at 96 mgdl before hypo threshold`() {
        val scenario = DebugScenario.DEMO_PREDICTIVE_HYPO_CATCH
        val values = scenario.values()

        // Point index 4 is 96 mg/dL (safely in normal range)
        assertEquals(96, values[4])

        val rate = scenario.customRateForPoint(4, values.size)
        assertNotNull(rate)
        assertEquals(-2.2, rate!!, 0.001)

        val severity = scenario.customSeverityForPoint(4, values.size, values[4])
        assertEquals("yellow", severity)

        val projected = scenario.customProjectedForPoint(4, values.size, values[4], rate)
        assertNotNull(projected)
        // 96 + (-2.2 * 18) = 56.4 -> 56 mg/dL
        assertEquals(56, projected!!.first)
        assertTrue("Projected value must be well below 70 mg/dL hypo threshold", projected.first!! < 70)
    }

    @Test
    fun `DEMO_TREATED_RECOVERY_SMART_MUTE exhibits TREATED tier and rising recovery`() {
        val scenario = DebugScenario.DEMO_TREATED_RECOVERY_SMART_MUTE
        val values = scenario.values()

        // Recovers from 65 to 118
        assertEquals(65, values.first())
        assertEquals(118, values.last())

        for (i in values.indices) {
            assertEquals(CauseTier.TREATED, scenario.causeTierForPoint(i, values.size))
            val rate = scenario.customRateForPoint(i, values.size)
            assertNotNull(rate)
            assertTrue("Recovery rate should be positive", rate!! > 0)
        }
    }

    @Test
    fun `DEMO_UNEXPLAINED_FALSE_REBOUND exhibits UNEXPLAINED tier across bounce`() {
        val scenario = DebugScenario.DEMO_UNEXPLAINED_FALSE_REBOUND
        val values = scenario.values()

        // Bounces 75 -> 88 -> 68
        assertEquals(75, values[0])
        assertEquals(88, values[3])
        assertEquals(68, values.last())

        for (i in values.indices) {
            assertEquals(CauseTier.UNEXPLAINED, scenario.causeTierForPoint(i, values.size))
            val severity = scenario.customSeverityForPoint(i, values.size, values[i])
            assertTrue("Severity must remain active across bounce", severity == "yellow" || severity == "red")
        }
    }

    @Test
    fun `DEMO_POST_MEAL_INSULIN_DECAY shows spike rollover and stabilization`() {
        val scenario = DebugScenario.DEMO_POST_MEAL_INSULIN_DECAY
        val values = scenario.values()

        // Starts 130, peaks 230, resolves 130
        assertEquals(130, values.first())
        assertEquals(230, values[5])
        assertEquals(130, values.last())

        // At peak rollover, rate turns negative
        val ratePeak = scenario.customRateForPoint(6, values.size)
        assertNotNull(ratePeak)
        assertTrue("Rate should turn negative after peak", ratePeak!! < 0)
    }

    @Test
    fun `DEMO_DELAYED_EXERCISE_RISK exhibits EXERCISE_RISK tier during nocturnal fall`() {
        val scenario = DebugScenario.DEMO_DELAYED_EXERCISE_RISK
        val values = scenario.values()

        for (i in values.indices) {
            assertEquals(CauseTier.EXERCISE_RISK, scenario.causeTierForPoint(i, values.size))
        }

        assertTrue("Should drop into vulnerable range", values.last() < 70)
    }
}
