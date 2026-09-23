package com.aheadt1d.app.alerts

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aheadt1d.app.notifications.GlucoseDisplayState
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import com.aheadt1d.app.state.LatestTrend
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * GAPS FOUND 2026-09-20 tracing the alert path end to end (review only - no source was changed).
 *
 * Same harness and same real entry point as AlertCoordinatorTest: everything goes through
 * AlertCoordinator.evaluate() against real SharedPreferences and a real NotificationManager, so
 * each test asserts what a person would actually observe - did something post, and what did it say.
 *
 * Tests here that FAIL are documenting live gaps, not flaky expectations. One test
 * (`offline...`) is expected to PASS: it is the positive evidence that the alerting path is
 * genuinely local and survives a total backend outage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertPathGapsTest {

    private lateinit var context: Context
    private lateinit var shadowNm: org.robolectric.shadows.ShadowNotificationManager
    private lateinit var nm: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        nm = context.getSystemService(NotificationManager::class.java)
        shadowNm = shadowOf(nm)
        for (p in listOf("ahead_alert_state", "ahead_alert_channels", "ahead_plateau_state", "ahead_alert_silence")) {
            context.getSharedPreferences(p, Context.MODE_PRIVATE).edit().clear().commit()
        }
        com.aheadt1d.app.state.LatestTrendRepository.clear(context)
    }

    private fun reading(
        value: Int,
        severity: String,
        ratePerMinute: Double? = 0.0,
        projected: Int? = null,
        readingTime: Long = System.currentTimeMillis(),
    ) = GlucoseDisplayState.Reading(
        value = value,
        arrow = GlucoseTrendArrow.FLAT,
        readingTime = readingTime,
        deltaFromPrevious = null,
        trendIsComputed = true,
        severity = severity,
        projected = projected,
        projectedExtended = null,
        ratePerMinute = ratePerMinute,
    )

    private fun trend(date: Long, currentValue: Int, severity: String) = LatestTrend(
        currentValue = currentValue, severity = severity, rate = null,
        projected = null, projectedExtended = null, date = date, guesses = emptyList(),
    )

    private fun stale(lastValue: Int, ageMinutes: Long) = GlucoseDisplayState.Stale(
        lastValue = lastValue,
        lastReadingTime = System.currentTimeMillis() - ageMinutes * 60_000L,
        ageMinutes = ageMinutes,
        lastArrow = GlucoseTrendArrow.FLAT,
    )

    private fun titleOf(id: Int): String? =
        shadowNm.getNotification(id)?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun anyPosted(): Boolean = shadowNm.allNotifications.isNotEmpty()

    // =================================================================================
    // GAP 1 - a total data blackout is silenced along with ordinary glucose alerts.
    // =================================================================================

    /**
     * AlertCoordinator.evaluate's FIRST statement is `if (AlertSilenceManager.isSilenced(context))
     * { cancel...; return }`, which sits above the `when (state)` that routes Stale ->
     * handleStale. So silence suppresses the signal-lost alert exactly as if it were a glucose
     * alert.
     *
     * Those are different things. Silencing says "stop telling me about my glucose"; it cannot
     * sensibly mean "and also stop telling me you have gone blind." handleStale's own doc argues
     * a blackout "is dangerous on its own, regardless of what the last confirmed severity was" and
     * escalates it to RED tier for that reason - silence overrides that argument entirely.
     *
     * Worse with silenceIndefinitely(), which is reachable from the normal UI (MainActivity:337
     * and :397, not only the debug menu) and never expires.
     */
    @Test
    fun `a data blackout must still reach the user while glucose alerts are silenced`() {
        AlertSilenceManager.silenceIndefinitely(context)

        // Signal has been gone for 45 minutes - three times the re-alert cooldown.
        AlertCoordinator.evaluate(context, stale(lastValue = 88, ageMinutes = 45), null)

        assertTrue(
            "no notification at all after a 45-minute blackout, because alerts were silenced - " +
                "silencing glucose alerts must not also disable 'I cannot see your glucose'",
            anyPosted(),
        )
    }

    /** The time-boxed version of the same hole: an ordinary 15-minute snooze does it too. */
    @Test
    fun `a data blackout must still reach the user during an ordinary snooze`() {
        AlertSilenceManager.silence(context, 15)
        AlertCoordinator.evaluate(context, stale(lastValue = 88, ageMinutes = 30), null)
        assertTrue("a 30-minute blackout was silent during a 15-minute snooze", anyPosted())
    }

    // =================================================================================
    // GAP 2 - a dismissed red never returns while the value is held in the low band.
    // =================================================================================

    /**
     * The low-red clear buffer (2026-09-23: a 2-consecutive-reading stability streak gated on
     * the app's own 70 mg/dL threshold, replacing the old flat 80 mg/dL LOW_RED_CLEAR_HYSTERESIS
     * band - see AlertCoordinator's LOW_STABILITY_READINGS_REQUIRED doc) keeps a fired red posted
     * until the episode is confirmed stable. It does that by RETURNING EARLY from
     * handleReading - before the `when (severity)` block and before any re-alert heartbeat - on
     * the assumption that the already-posted notification is still sitting in the tray.
     *
     * Notifications are dismissible. Red is built with setAutoCancel(true), so tapping it removes
     * it, and it can be swiped away. Once it is gone, the hysteresis branch keeps returning early
     * and nothing re-posts: the person is still at 71-79 after a real low and the app is showing
     * them nothing at all until they either drop back under the red line or clear 80.
     */
    @Test
    fun `a dismissed red must come back while the value is still held in the low band`() {
        // A real low fires red.
        AlertCoordinator.evaluate(context, reading(value = 62, severity = "red"), trend(1L, 62, "red"))
        assertNotNull("setup: expected the initial red to post", titleOf(AlertNotifier.RED_ALERT_NOTIFICATION_ID))

        // The person taps/swipes it away (setAutoCancel(true) does this on tap).
        nm.cancelAll()

        // They are climbing back but are still inside the low band, so the hysteresis holds.
        // Several cycles pass, each a new reading.
        val start = System.currentTimeMillis()
        for (i in 1..6) {
            AlertCoordinator.evaluate(
                context,
                reading(value = 75, severity = "yellow", ratePerMinute = 0.4, readingTime = start + i * 300_000L),
                trend(start + i * 300_000L, 75, "yellow"),
            )
        }

        assertTrue(
            "after dismissing the red at 62 and sitting at 75 for six cycles, nothing is posted - " +
                "the hysteresis assumes a notification it never re-posts",
            anyPosted(),
        )
    }

    // =================================================================================
    // GAP 3 - yellow has no time cooldown, so flapping re-alarms every cycle.
    // =================================================================================

    /**
     * Red has RED_LOW_REALERT_COOLDOWN_MS / RED_HIGH_REALERT_COOLDOWN_MS. Yellow has NO time-based
     * cooldown of any kind. fireYellowIfWarranted's only rate limit is
     * YELLOW_MATERIAL_WORSENING_MGDL, and that applies solely to a CONTINUING episode; entry into
     * yellow (`forceFire = prevSeverity != "yellow"`) always posts, and the "none" branch wipes
     * KEY_YELLOW_LAST_ALERTED_PROJECTED on the way past.
     *
     * A value parked on the boundary - the 15-min projection crossing 80 back and forth, which is
     * ordinary CGM noise - therefore alarms on EVERY re-entry. This is the same alarm-fatigue
     * shape as the 2026-08-01 red "recovery just stopped" bug that MIN_REALERT_GAP_MS was added
     * to fix; yellow never got the equivalent floor.
     */
    @Test
    fun `yellow flapping across the boundary must not re-alarm on every re-entry`() {
        val start = System.currentTimeMillis()
        var audible = 0
        // Twelve 5-minute cycles = one hour, alternating just-yellow / just-none.
        for (i in 0 until 12) {
            val date = start + i * 300_000L
            val isYellow = i % 2 == 0
            nm.cancelAll() // only count freshly-posted alerts, not ones left in the tray
            AlertCoordinator.evaluate(
                context,
                if (isYellow) reading(79, "yellow", ratePerMinute = -0.2, projected = 79, readingTime = date)
                else reading(82, "none", ratePerMinute = 0.2, projected = 82, readingTime = date),
                trend(date, if (isYellow) 79 else 82, if (isYellow) "yellow" else "none"),
            )
            if (titleOf(AlertNotifier.YELLOW_ALERT_NOTIFICATION_ID) != null) audible++
        }
        assertTrue(
            "$audible yellow alerts in one simulated hour from a value wobbling across the " +
                "boundary - yellow needs a minimum re-alert gap like red's MIN_REALERT_GAP_MS",
            audible <= 3,
        )
    }

    // =================================================================================
    // GAP 4 - NoData was the app's one completely silent failure mode.
    // =================================================================================

    /**
     * A fresh install genuinely has nothing to say, and must stay quiet - this guards the fix
     * below from turning every first launch into a blackout alarm.
     */
    @Test
    fun `a fresh install with no reading ever is correctly silent`() {
        AlertCoordinator.evaluate(context, GlucoseDisplayState.NoData, null)
        assertTrue("a fresh install must not alarm", shadowNm.allNotifications.isEmpty())
    }

    /**
     * But once a reading HAS been handled, losing it entirely is a blackout wearing a different
     * costume. Before the fix the NoData branch was `{ /* nothing to alert on */ }` either way,
     * which made this the only path through evaluate() that could go completely quiet.
     */
    @Test
    fun `losing the stored reading after having one is announced, not silent`() {
        AlertCoordinator.evaluate(context, reading(value = 96, severity = "none"), trend(1L, 96, "none"))
        nm.cancelAll()

        AlertCoordinator.evaluate(context, GlucoseDisplayState.NoData, null)

        assertTrue(
            "the app had a reading at 96 and then had none at all - that must be announced",
            anyPosted(),
        )
    }

    // =================================================================================
    // POSITIVE EVIDENCE - the alerting path is local and survives a dead backend.
    // =================================================================================

    /**
     * Expected to PASS. LatestTrend is the only backend-derived input AlertCoordinator receives,
     * and this passes null for it - the state a phone is in when Railway is unreachable and no
     * trend has ever been stored. The red still fires, from the locally-computed severity alone.
     */
    @Test
    fun `offline - a red still fires with no backend trend at all`() {
        AlertCoordinator.evaluate(context, reading(value = 58, severity = "red", ratePerMinute = -2.0), null)
        val title = titleOf(AlertNotifier.RED_ALERT_NOTIFICATION_ID)
        assertNotNull("a red must fire with no backend trend present", title)
        assertTrue("expected the value in the title, got: $title", title?.contains("58") == true)
    }

    /** Expected to PASS: the blackout alert itself needs no backend either. */
    @Test
    fun `offline - a signal-lost alert fires with no backend trend at all`() {
        AlertCoordinator.evaluate(context, stale(lastValue = 104, ageMinutes = 25), null)
        assertTrue("a blackout must be announced without any backend involvement", anyPosted())
    }
}
