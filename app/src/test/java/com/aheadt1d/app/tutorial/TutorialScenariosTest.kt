package com.aheadt1d.app.tutorial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialScenariosTest {

    @Test
    fun `repository provides three canonical clinical scenarios`() {
        val scenarios = TutorialRepository.scenarios
        assertEquals(3, scenarios.size)

        val yellowScenario = scenarios[0]
        assertEquals("drop_yellow_hold_horses", yellowScenario.id)
        assertEquals(4, yellowScenario.steps.size)
        // Step 1: 141 (baseline)
        assertEquals(141, yellowScenario.steps[0].value)
        assertEquals("none", yellowScenario.steps[0].severity)
        // Step 2: 132 (alert)
        assertEquals(132, yellowScenario.steps[1].value)
        assertEquals("yellow", yellowScenario.steps[1].severity)
        assertTrue(yellowScenario.steps[1].alertFired)
        // Step 3: 124 (2nd reading)
        assertEquals(124, yellowScenario.steps[2].value)
        assertEquals("yellow", yellowScenario.steps[2].severity)
        assertFalse(yellowScenario.steps[2].alertFired)
        // Step 4: 119 (leveled off safe)
        assertEquals(119, yellowScenario.steps[3].value)
        assertEquals("none", yellowScenario.steps[3].severity)

        val redScenario = scenarios[1]
        assertEquals("plunge_red_urgent_action", redScenario.id)
        assertEquals("red", redScenario.steps[1].severity)
        assertTrue(redScenario.steps[1].alertFired)

        val spikeScenario = scenarios[2]
        assertEquals("spike_yellow_prevent_stack", spikeScenario.id)
        assertEquals(204, spikeScenario.steps[2].value)
    }

    @Test
    fun `all scenario steps contain coaching and rule of thumb guidance`() {
        for (scenario in TutorialRepository.scenarios) {
            for (step in scenario.steps) {
                assertNotNull(step.coachingHeader)
                assertTrue(step.coachingHeader.isNotBlank())
                assertNotNull(step.coachingBody)
                assertTrue(step.coachingBody.isNotBlank())
                assertNotNull(step.aheadRuleOfThumb)
                assertTrue(step.aheadRuleOfThumb.isNotBlank())
                assertNotNull(step.userActionPrompt)
                assertTrue(step.userActionPrompt.isNotBlank())
            }
        }
    }
}
