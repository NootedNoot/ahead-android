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
 * Proves toDisplayState() actually wires RawReading's fields through to
 * SeverityEngine.classify() - starting with recoveringFromLow, found
 * missing entirely during a 2026-08-28/29 fragmentation audit
 * (SeverityEngine's post-hypo recovery grace period was built and
 * unit-tested in isolation, but the one real call site never passed this
 * parameter, so it silently defaulted to false always and the feature was
 * inert on-device). The same pass found recentRates was ALSO never passed
 * (meaning trajectory classification - DECELERATING/NOISY - never engaged
 * on-device at all) and wired in RateConsensus/TreatmentEffectWindow
 * (the "smarter math" additions) on top of that fix - see the later tests
 * in this file for those. GlucoseCheckRunnerTest would be the more
 * end-to-end place to test the Health-Connect-history -> RawReading
 * computation itself, but that needs a mocked HealthConnectClient this
 * repo doesn't have test infra for yet - these tests cover the half of the
 * chain that was actually missing: does each field, once set, reach the
 * classifier and change its real output.
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

    @Test
    fun `severityRatePerMinute drives the alert decision, but the displayed rate stays the raw one`() {
        // ratePerMinute (the raw 2-point number) says a calm +0.2 - nothing
        // alert-worthy. severityRatePerMinute (RateConsensus's median of
        // three estimates) says a genuinely fast +3.0 rise, high enough
        // (with a high enough value) to be a real fast-rise yellow. This
        // proves toDisplayState() actually prefers severityRatePerMinute
        // for the SEVERITY decision (2026-08-29 wiring pass) - and, just as
        // importantly, that Reading.ratePerMinute (what the arrow/number on
        // screen actually shows) still reflects the raw, unmodified rate,
        // never the consensus one. Someone looking at their screen should
        // never see a number that disagrees with their own sensor app.
        val raw = RawReading(
            value = 170,
            time = System.currentTimeMillis(),
            ratePerMinute = 0.2,
            deltaFromPrevious = 1,
            severityRatePerMinute = 3.0,
        )
        val state = toDisplayState(context, raw, trend = null, blocked = null)
        val reading = state as? GlucoseDisplayState.Reading
        assertEquals("yellow", reading?.severity)
        assertEquals(0.2, reading?.ratePerMinute!!, 0.0001)
    }

    @Test
    fun `recentRates and excursionDurationMinutes both reach the real classifier through the full RawReading path`() {
        // Same shape as SeverityEngine's own
        // "excursionDurationMinutes changes classify's real output" test in
        // ahead-rate-math, but exercised end-to-end starting from a
        // RawReading through the actual toDisplayState() entry point -
        // proving the WHOLE chain (RawReading -> toDisplayState ->
        // SeverityEngine.classify) works together, not just that each half
        // works in isolation.
        val currentValue = 195
        val rate = 0.6
        val recentRates = listOf(1.4, 1.0, 0.6)

        val fullyTrusted = toDisplayState(
            context,
            RawReading(value = currentValue, time = System.currentTimeMillis(), ratePerMinute = rate, deltaFromPrevious = 0, recentRates = recentRates),
            trend = null, blocked = null,
        ) as GlucoseDisplayState.Reading
        assertEquals("none", fullyTrusted.severity ?: "none")

        val earlyInExcursion = toDisplayState(
            context,
            RawReading(
                value = currentValue, time = System.currentTimeMillis(), ratePerMinute = rate, deltaFromPrevious = 0,
                recentRates = recentRates, excursionDurationMinutes = 5L,
            ),
            trend = null, blocked = null,
        ) as GlucoseDisplayState.Reading
        assertEquals("yellow", earlyInExcursion.severity)
    }
}
