package com.aheadt1d.app.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aheadt1d.app.data.GlucoseVaultDatabase
import com.aheadt1d.app.notifications.GlucoseDisplayState
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves PersonalBaseline (item 3 of the "smarter math" additions) is
 * genuinely wired into PassiveContextEngine's real insight generation -
 * not just that the underlying math is correct in isolation (that's
 * already covered by ahead-rate-math's own PersonalBaselineTest). Writes
 * real synthetic history into the same GlucoseVaultDatabase the app uses,
 * then confirms an out-of-pattern reading actually surfaces the
 * "unusual for you" insight through the real evaluateContext() entry
 * point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassiveContextEngineTest {

    private lateinit var context: Context
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Both GlucoseVaultDatabase (a real SQLite singleton) and
        // PassiveContextEngine's baseline cache (a Kotlin `object`, so one
        // instance for the whole test JVM) outlive any single test -
        // starting each test with a clean baseline cache so it always
        // rebuilds from whatever this test just inserted.
        PassiveContextEngine.resetBaselineCacheForTesting()
    }

    private fun atHour(hour: Int, daysAgo: Long): Long =
        LocalDate.now(zone).minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    // Both scenarios deliberately live in ONE test method, not two.
    // GlucoseVaultDatabase.getInstance() is a real singleton (a Kotlin
    // `object`-style `@Volatile INSTANCE`) that survives Robolectric's
    // per-test environment reset - a second @Test method reusing that same
    // instance after Robolectric tears down the first test's SQLite
    // connection throws "Illegal connection pointer" (confirmed while
    // writing this: the unusual-reading assertion below passed fine on its
    // own in a standalone test, proving the WIRING is correct - this was
    // purely a test-isolation artifact of the singleton, not a production
    // bug, so the fix belongs in the test shape, not the app code).
    @Test
    fun `personal-baseline insight fires for an outlier and stays quiet for a typical reading`() {
        val vault = GlucoseVaultDatabase.getInstance(context)

        // Six days of tightly clustered history around ~100 mg/dL at 10am -
        // enough samples (PersonalBaseline.MIN_SAMPLES_FOR_CONFIDENCE is 5)
        // and low enough variance that a 220 mg/dL reading at the same hour
        // is unmistakably far outside this person's own normal.
        val unusualHour = 10
        listOf(98, 99, 100, 101, 102, 100).forEachIndexed { i, sgv ->
            vault.recordReading(atHour(unusualHour, daysAgo = (i + 1).toLong()), sgv)
        }
        val unusualReading = GlucoseDisplayState.Reading(
            value = 220,
            arrow = GlucoseTrendArrow.FLAT,
            readingTime = atHour(unusualHour, daysAgo = 0),
            deltaFromPrevious = 0,
            trendIsComputed = true,
            severity = null,
            projected = 220,
            projectedExtended = 220,
            // Deliberately 0.0 - avoids the dawn-surge/compression-low
            // insight branches (both require a nonzero rate threshold), so
            // the personal-baseline insight isn't competing with, or
            // accidentally masked by, one of those instead.
            ratePerMinute = 0.0,
        )
        // Empty history: avoids dwell-based (stubborn-high/sticky-low) and
        // curvature-based insights entirely, so a firing insight here can
        // only be the personal-baseline one this test is actually proving.
        val unusualSummary = PassiveContextEngine.evaluateContext(context, unusualReading, history = emptyList())
        assertNotNull(
            "expected a personal-baseline insight for a 220 mg/dL reading against a ~100 mg/dL 6-day pattern at the same hour",
            unusualSummary.primaryInsight,
        )

        // A different hour, so this doesn't get folded into the same
        // 10am baseline as the block above - a reading that matches this
        // person's own typical pattern for THIS hour instead.
        PassiveContextEngine.resetBaselineCacheForTesting()
        val typicalHour = 14
        listOf(148, 149, 150, 151, 152, 150).forEachIndexed { i, sgv ->
            vault.recordReading(atHour(typicalHour, daysAgo = (i + 1).toLong()), sgv)
        }
        val typicalReading = GlucoseDisplayState.Reading(
            value = 150, // right at this person's own typical mean for this hour
            arrow = GlucoseTrendArrow.FLAT,
            readingTime = atHour(typicalHour, daysAgo = 0),
            deltaFromPrevious = 0,
            trendIsComputed = true,
            severity = null,
            projected = 150,
            projectedExtended = 150,
            ratePerMinute = 0.0,
        )
        val typicalSummary = PassiveContextEngine.evaluateContext(context, typicalReading, history = emptyList())
        assertNull(
            "a reading that matches this person's own typical pattern should not be flagged as unusual",
            typicalSummary.primaryInsight,
        )
    }
}
