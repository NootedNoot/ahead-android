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
    fun `recovery requires clearing the danger band, not just the floor`() {
        assertFalse("56 cleared the 55 floor but hasn't recovered", CriticalLowMath.hasRecovered(56))
        assertFalse(CriticalLowMath.hasRecovered(69))
        // 2026-08-01: raised 70 -> 75. A 73 ladder rung is unreachable if
        // anything at/above 70 short-circuits as "recovered" in check().
        assertFalse("73 is a ladder rung, it must not read as recovered", CriticalLowMath.hasRecovered(73))
        assertFalse(CriticalLowMath.hasRecovered(74))
        assertTrue(CriticalLowMath.hasRecovered(75))
        assertTrue(CriticalLowMath.hasRecovered(85))
    }

    @Test
    fun `default constants match the documented values`() {
        assertEquals(55, CriticalLowMath.DEFAULT_FLOOR)
        assertEquals(75, CriticalLowMath.RECOVERY_THRESHOLD)
        assertEquals(73, CriticalLowMath.TANKING_ENTRY)
    }

    @Test
    fun `the ladder sits entirely between the floor and the recovery threshold`() {
        // Guards the whole design: a rung at or above RECOVERY_THRESHOLD could
        // never fire (check() tests hasRecovered first), and a rung at or
        // below DEFAULT_FLOOR belongs to the emergency band instead.
        for (rung in CriticalLowMath.TANKING_RUNGS) {
            assertTrue("rung $rung must be above the emergency floor", rung > CriticalLowMath.DEFAULT_FLOOR)
            assertTrue("rung $rung must be below the recovery threshold", rung < CriticalLowMath.RECOVERY_THRESHOLD)
        }
        assertEquals(
            "TANKING_ENTRY must be the top rung",
            CriticalLowMath.TANKING_RUNGS.max(),
            CriticalLowMath.TANKING_ENTRY,
        )
    }

    @Test
    fun `falling through the entry band counts as tanking`() {
        assertTrue(CriticalLowMath.isTanking(73, rate = -1.0)) // top rung, at the rate threshold
        assertTrue(CriticalLowMath.isTanking(65, rate = -1.5))
        assertTrue(CriticalLowMath.isTanking(56, rate = -2.0))
    }

    @Test
    fun `flat or rising in the same band is not tanking`() {
        assertFalse("flat isn't tanking", CriticalLowMath.isTanking(65, rate = 0.0))
        assertFalse("rising isn't tanking", CriticalLowMath.isTanking(65, rate = 1.2))
        assertFalse("slow decline under the rate threshold isn't tanking", CriticalLowMath.isTanking(65, rate = -0.5))
        assertFalse("unknown rate can't be tanking", CriticalLowMath.isTanking(65, rate = null))
    }

    @Test
    fun `already critical or above the ladder is never tanking`() {
        assertFalse("55 is critical, not tanking", CriticalLowMath.isTanking(55, rate = -3.0))
        assertFalse("40 is critical, not tanking", CriticalLowMath.isTanking(40, rate = -3.0))
        assertFalse("74 is above the top rung", CriticalLowMath.isTanking(74, rate = -3.0))
    }

    @Test
    fun `deepestRungCrossed reports the worst rung reached`() {
        assertEquals(null, CriticalLowMath.deepestRungCrossed(80))
        assertEquals(null, CriticalLowMath.deepestRungCrossed(74))
        assertEquals(73, CriticalLowMath.deepestRungCrossed(73))
        assertEquals(73, CriticalLowMath.deepestRungCrossed(71))
        assertEquals(70, CriticalLowMath.deepestRungCrossed(70))
        assertEquals(70, CriticalLowMath.deepestRungCrossed(68))
        assertEquals(67, CriticalLowMath.deepestRungCrossed(64))
        assertEquals(63, CriticalLowMath.deepestRungCrossed(63))
        assertEquals(63, CriticalLowMath.deepestRungCrossed(40))
    }
}
