package com.aheadt1d.app.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aheadt1d.app.alerts.PlateauCoordinator
import com.aheadt1d.app.events.EventTag
import com.aheadt1d.app.events.UserEventRepository
import com.aheadt1d.app.tuning.ExerciseTuningParameters
import com.aheadt1d.app.tuning.ExerciseTuningPrefs
import kotlinx.coroutines.runBlocking
import org.aheadt1d.ratemath.CauseTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LatestTrendRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        com.aheadt1d.app.events.AppDatabase.resetForTesting()
        context.deleteDatabase("ahead_events.db")
        LatestTrendRepository.clear(context)
        for (p in listOf("ahead_plateau_state", "ahead_exercise_tuning", "ahead_raw_reading", "ahead_latest_trend")) {
            context.getSharedPreferences(p, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @org.junit.After
    fun tearDown() {
        com.aheadt1d.app.events.AppDatabase.resetForTesting()
        context.deleteDatabase("ahead_events.db")
    }

    private fun raw(value: Int, time: Long, causeTier: CauseTier? = null) = RawReading(
        value = value,
        time = time,
        ratePerMinute = 0.0,
        deltaFromPrevious = 0,
        causeTier = causeTier,
    )

    @Test
    fun `dual-writer race protection - untiered write preserves existing causeTier for same timestamp`() {
        val timestamp = 1_700_000_000_000L
        val tieredReading = raw(value = 65, time = timestamp, causeTier = CauseTier.TREATED)
        LatestTrendRepository.updateRawReading(context, tieredReading)
        assertEquals(CauseTier.TREATED, LatestTrendRepository.latestRawReading.value?.causeTier)

        // Untiered write (e.g. from an unpatched or raced caller) for the same timestamp:
        val untieredReading = raw(value = 65, time = timestamp, causeTier = null)
        LatestTrendRepository.updateRawReading(context, untieredReading)
        // Must preserve the already-computed TREATED tier:
        assertEquals(
            "Existing non-null causeTier must not be stomped by null for the same reading timestamp",
            CauseTier.TREATED,
            LatestTrendRepository.latestRawReading.value?.causeTier,
        )

        // A new reading at a later timestamp with null causeTier is permitted to set its own tier:
        val newUntieredReading = raw(value = 64, time = timestamp + 300_000L, causeTier = null)
        LatestTrendRepository.updateRawReading(context, newUntieredReading)
        assertNull(LatestTrendRepository.latestRawReading.value?.causeTier)
    }

    @Test
    fun `withComputedCauseTier resolves TREATED when active correction exists`() = runBlocking {
        val now = 1_700_000_000_000L
        PlateauCoordinator.onCorrectionLogged(context, now, explicitLow = true, glucoseAtTime = 65)

        val reading = raw(value = 65, time = now)
        val tiered = reading.withComputedCauseTier(context, now = now + 10_000L)
        assertEquals(CauseTier.TREATED, tiered.causeTier)
    }

    @Test
    fun `withComputedCauseTier resolves EXERCISE_ACTIVE during immediate workout window`() = runBlocking {
        val now = 1_700_000_000_000L
        UserEventRepository.log(context, EventTag.EXERCISE, timestamp = now - 20 * 60_000L)

        val reading = raw(value = 68, time = now)
        val tiered = reading.withComputedCauseTier(context, now = now)
        assertEquals(CauseTier.EXERCISE_ACTIVE, tiered.causeTier)
    }

    @Test
    fun `withComputedCauseTier resolves EXERCISE_RISK and respects ExerciseTuningPrefs`() = runBlocking {
        val now = 1_700_000_000_000L
        // Custom risk window of 6 hours
        ExerciseTuningPrefs.save(context, ExerciseTuningParameters(exerciseRiskWindowHours = 6))

        // 4 hours ago is within 6-hour risk window -> EXERCISE_RISK
        val exerciseTime = now - 4 * 3600_000L
        UserEventRepository.log(context, EventTag.EXERCISE, timestamp = exerciseTime)

        val reading = raw(value = 68, time = now)
        val tiered4h = reading.withComputedCauseTier(context, now = now)
        assertEquals(CauseTier.EXERCISE_RISK, tiered4h.causeTier)

        // 8 hours ago (4 hours later than now) is past 6-hour window -> UNEXPLAINED
        val tiered8h = reading.withComputedCauseTier(context, now = now + 4 * 3600_000L)
        assertEquals(CauseTier.UNEXPLAINED, tiered8h.causeTier)
    }

    @Test
    fun `withComputedCauseTier resolves UNEXPLAINED on high side even with recent exercise`() = runBlocking {
        val now = 1_700_000_000_000L
        UserEventRepository.log(context, EventTag.EXERCISE, timestamp = now - 10 * 60_000L)

        val rawHigh = raw(value = 220, time = now)
        val tieredHigh = rawHigh.withComputedCauseTier(context, now = now)
        assertEquals(CauseTier.UNEXPLAINED, tieredHigh.causeTier)
    }
}
