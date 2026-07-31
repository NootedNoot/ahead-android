package com.aheadt1d.app.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalLowMathTest {

    @Test
    fun `at the default floor is critical`() {
        assertTrue(CriticalLowMath.isCriticalLow(55))
    }

    @Test
    fun `one above the default floor is not critical`() {
        assertFalse(CriticalLowMath.isCriticalLow(56))
    }

    @Test
    fun `well below the floor is critical`() {
        assertTrue(CriticalLowMath.isCriticalLow(40))
    }

    @Test
    fun `custom floor is respected`() {
        assertTrue(CriticalLowMath.isCriticalLow(58, floor = 60))
        assertFalse(CriticalLowMath.isCriticalLow(61, floor = 60))
    }

    @Test
    fun `recovery requires reaching the threshold, not just clearing the floor`() {
        assertFalse("56 cleared the 55 floor but hasn't recovered", CriticalLowMath.hasRecovered(56))
        assertFalse(CriticalLowMath.hasRecovered(69))
        assertTrue(CriticalLowMath.hasRecovered(70))
        assertTrue(CriticalLowMath.hasRecovered(85))
    }

    @Test
    fun `default constants match the documented values`() {
        assertEquals(55, CriticalLowMath.DEFAULT_FLOOR)
        assertEquals(70, CriticalLowMath.RECOVERY_THRESHOLD)
    }
}
