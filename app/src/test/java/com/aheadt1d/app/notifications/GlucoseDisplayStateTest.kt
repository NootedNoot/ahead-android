package com.aheadt1d.app.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aheadt1d.app.state.RawReading
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves toDisplayState() actually wires RawReading.recoveringFromLow through
 * to SeverityEngine.classify() - found missing entirely during a
 * 2026-08-28/29 fragmentation audit (SeverityEngine's post-hypo recovery
 * grace period was built and unit-tested in isolation, but the one real call
 * site never passed this parameter, so it silently defaulted to false always
 * and the feature was inert on-device). GlucoseCheckRunnerTest would be the
 * more end-to-end place to test this (proving the Health-Connect-history ->
 * RawReading.recoveringFromLow computation itself), but that needs a mocked
 * HealthConnectClient this repo doesn't have test infra for yet - this test
 * covers the half of the chain that was actually missing: does the field,
 * once set, reach the classifier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlucoseDisplayStateTest {

    private lateinit var context: Context

    @org.junit.Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `recoveringFromLow suppresses what would otherwise be a real alert`() {
        // 170 mg/dL rising at +3.0 mg/dL/min IS a fast-rise yellow on its own
        // (rate >= RATE_RISING_TRIGGER and value >= VULNERABLE_RISE_FLOOR_MGDL,
        // both true here) - exactly the kind of expected post-treatment
        // rebound the grace period exists to keep quiet (projects to 215,
        // under both RECOVERY_REBOUND_CEILING_MGDL and redProjectedHigh).
        val raw = RawReading(
            value = 170,
            time = System.currentTimeMillis(),
            ratePerMinute = 3.0,
            deltaFromPrevious = 15,
            recoveringFromLow = true,
        )
        val state = toDisplayState(context, raw, trend = null, blocked = null)
        val reading = state as? GlucoseDisplayState.Reading
        assertEquals("none", reading?.severity ?: "none")
    }

    @Test
    fun `the same reading without recoveringFromLow fires the alert it should`() {
        // Same value/rate as above, but NOT flagged as recovering - this
        // should NOT be suppressed, confirming the previous test's "none"
        // result comes from the grace period actually engaging, not from
        // some other reason a 170-rising-fast reading might read as safe.
        val raw = RawReading(
            value = 170,
            time = System.currentTimeMillis(),
            ratePerMinute = 3.0,
            deltaFromPrevious = 15,
            recoveringFromLow = false,
        )
        val state = toDisplayState(context, raw, trend = null, blocked = null)
        val reading = state as? GlucoseDisplayState.Reading
        assertEquals("yellow", reading?.severity)
    }
}
