package com.aheadt1d.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * GlucoseVaultDatabase.getDailySummaries() had a REAL live bug (2026-08-25,
 * see that function's own doc comment): grouping by the UTC date substring
 * instead of the device-local date silently split one real local day across
 * two mislabeled rows on a UTC-6 device. That bug shipped and was only
 * caught by manual live testing on a real device in a non-UTC timezone -
 * zero automated coverage existed for it before this file.
 *
 * IMPORTANT, hard-won finding from writing this test (2026-08-30): this
 * function's `date(epoch_millis/1000,'unixepoch','localtime')` query is
 * answered by SQLite's NATIVE C runtime, which reads the actual host
 * machine's real system timezone - confirmed by direct probe. Neither
 * `java.util.TimeZone.setDefault(...)` (has zero effect - proven directly:
 * `TimeZone.getDefault()` correctly reported the override while SQLite's
 * own `date(...,'localtime')` output didn't move at all) nor setting the
 * `TZ` process environment variable to an IANA zone name like
 * "America/Chicago" (the native runtime appears to expect POSIX-style TZ
 * strings like "CST6CDT", not IANA path names - the probe's `SELECT
 * strftime('now','localtime')` came back with a nonsensical ~1h offset
 * from UTC when TZ was set to an IANA name, not the correct -5h/-6h) can
 * reliably fake a different timezone for this specific query inside one
 * test run. That means genuine multi-timezone confidence for this
 * function needs a real device/emulator with its OS timezone actually
 * changed, or a CI runner whose real system timezone is deliberately set
 * - not achievable purely inside this Kotlin/Robolectric test.
 *
 * So this file tests what CAN be honestly proven from a JVM unit test:
 * the exact boundary case that would have caught the original bug,
 * using whatever the test host's REAL system timezone happens to be
 * (read dynamically, never assumed) - cross-checked against java.time's
 * independent, non-SQLite computation for that same real zone. This
 * still exercises real non-UTC local-day math (this dev environment's
 * real system zone is a US zone, confirmed non-UTC by the probe above),
 * it just can't parameterize over multiple zones in one run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlucoseVaultDatabaseTimezoneTest {

    private lateinit var context: Context
    private lateinit var hostZone: ZoneId

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // The test host's REAL system default - read, never set. If this
        // ever comes back as UTC, these tests silently stop exercising the
        // actual bug (UTC and local would coincide) - the guard test below
        // catches that case explicitly rather than passing for the wrong
        // reason.
        hostZone = TimeZone.getDefault().toZoneId()
    }

    private fun freshVault(dbSuffix: String): GlucoseVaultDatabase {
        val name = "ahead_vault_tz_test_$dbSuffix.db"
        context.deleteDatabase(name)
        return GlucoseVaultDatabase(context, name)
    }

    @Test
    fun `guard - this test host's real system zone is genuinely non-UTC`() {
        // If this ever fails, every test below needs a second look - they'd
        // still pass, but for the trivial reason that local time equals UTC
        // on whatever machine ran them, not because the local-day math is
        // actually correct for a real non-UTC user.
        assertEquals("expected the dev/CI host's real system timezone to be non-UTC so these tests exercise real local-day math", false, hostZone.id == "UTC" || hostZone.id == "Etc/UTC")
    }

    @Test
    fun `a reading 1 second before and 1 second after local midnight land in different day buckets`() {
        // The precise boundary case, not just "roughly the right number of
        // buckets" - this is exactly what would have caught the original
        // 2026-08-25 bug directly, since that bug's symptom was one real
        // local day getting split across two mislabeled rows.
        val vault = freshVault("boundary")
        val localMidnight = ZonedDateTime.of(LocalDate.of(2026, 6, 16), LocalTime.MIDNIGHT, hostZone).toInstant()
        val justBefore = localMidnight.minusSeconds(1)
        val justAfter = localMidnight.plusSeconds(1)

        vault.recordReading(epochMillis = justBefore.toEpochMilli(), sgv = 100)
        vault.recordReading(epochMillis = justAfter.toEpochMilli(), sgv = 105)
        val summaries = vault.getDailySummaries()
        vault.close()

        assertEquals("1 second on either side of local midnight must land in 2 different day buckets", 2, summaries.size)
        val dates = summaries.map { it.isoDate }.sorted()
        assertEquals(LocalDate.ofInstant(justBefore, hostZone).toString(), dates[0])
        assertEquals(LocalDate.ofInstant(justAfter, hostZone).toString(), dates[1])
    }

    @Test
    fun `a 48h span anchored at real local midnight buckets into exactly the 2 calendar days java time expects`() {
        val vault = freshVault("fortyeight")
        val start = ZonedDateTime.of(LocalDate.of(2026, 6, 15), LocalTime.MIDNIGHT, hostZone).toInstant()
        val expectedDates = mutableSetOf<String>()
        for (i in 0 until 96) {
            val epoch = start.plusSeconds(i * 30L * 60L)
            vault.recordReading(epochMillis = epoch.toEpochMilli(), sgv = 100 + (i % 20))
            expectedDates.add(LocalDate.ofInstant(epoch, hostZone).toString())
        }
        val summaries = vault.getDailySummaries()
        vault.close()

        val actualDates = summaries.map { it.isoDate }.toSet()
        assertEquals("SQL day-bucketing must match java.time's independent computation for the host's real zone", expectedDates, actualDates)
        assertEquals(2, actualDates.size)
    }
}
