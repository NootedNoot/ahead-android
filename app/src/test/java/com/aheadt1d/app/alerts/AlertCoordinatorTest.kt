package com.aheadt1d.app.alerts

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aheadt1d.app.notifications.GlucoseDisplayState
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import com.aheadt1d.app.state.LatestTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the actual cooldown/hysteresis/peak-tracking DECISIONS in
 * AlertCoordinator - the part with no prior coverage (only the pure math in
 * PlateauMath/CorrectionResponseMath was tested). Runs through the real
 * evaluate() entry point under Robolectric (real Context/SharedPreferences/
 * NotificationManager) rather than reaching into private prefs keys, so
 * these assert the same thing a user would actually observe: did a
 * notification post, and what does it say.
 *
 * VoiceAlertPrefs defaults to enabled, so these do exercise VoiceAlertEngine -
 * Robolectric's TextToSpeech shadow handles that without a real engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertCoordinatorTest {

    private lateinit var context: Context
    private lateinit var shadowNm: org.robolectric.shadows.ShadowNotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // POST_NOTIFICATIONS is a runtime permission (API 33+) - Robolectric
        // does not auto-grant it just because it's declared in the manifest,
        // so notifyIfAllowed's checkSelfPermission gate silently no-ops every
        // notify() call unless this is granted explicitly.
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowNm = shadowOf(nm)
        // Fresh prefs every test - AlertCoordinator/AlertChannels both persist
        // to real SharedPreferences under Robolectric, which otherwise leak
        // between tests in the same class.
        context.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ahead_alert_channels", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ahead_plateau_state", Context.MODE_PRIVATE).edit().clear().commit()
        com.aheadt1d.app.state.LatestTrendRepository.clear(context)
    }

    /** Logs a correction the way EventLogDialogs' real hook does, at whatever
     *  glucose value determines high vs low direction - see
     *  PlateauCoordinator.onCorrectionLogged's doc. */
    private fun logCorrection(glucoseValue: Int, timestamp: Long = System.currentTimeMillis()) {
        com.aheadt1d.app.state.LatestTrendRepository.updateRawReading(
            context,
            com.aheadt1d.app.state.RawReading(value = glucoseValue, time = timestamp, ratePerMinute = null, deltaFromPrevious = null),
        )
        PlateauCoordinator.onCorrectionLogged(context, timestamp)
    }

    private fun reading(
        value: Int,
        severity: String,
        ratePerMinute: Double? = 0.0,
        projected: Int? = null,
    ) = GlucoseDisplayState.Reading(
        value = value,
        arrow = GlucoseTrendArrow.FLAT,
        readingTime = System.currentTimeMillis(),
        deltaFromPrevious = null,
        trendIsComputed = true,
        severity = severity,
        projected = projected,
        projectedExtended = null,
        ratePerMinute = ratePerMinute,
    )

    private fun trend(date: Long, currentValue: Int, severity: String) = LatestTrend(
        currentValue = currentValue,
        severity = severity,
        rate = null,
        projected = null,
        projectedExtended = null,
        date = date,
        guesses = emptyList(),
    )

    private fun redTitle(): String? =
        shadowNm.getNotification(AlertNotifier.RED_ALERT_NOTIFICATION_ID)
            ?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test
    fun `new low red fires an immediate full takeover alert`() {
        AlertCoordinator.evaluate(context, reading(value = 55, severity = "red"), trend(1L, 55, "red"))

        val title = redTitle()
        assertTrue("expected a red alert to post, got: $title", title?.contains("URGENT") == true)
        assertTrue(title?.contains("55") == true)
    }

    @Test
    fun `low red heartbeat while recovering is suppressed, not reposted`() {
        AlertCoordinator.evaluate(context, reading(value = 55, severity = "red"), trend(1L, 55, "red"))
        val firstTitle = redTitle()

        // Same severity+date (heartbeat), but now rising - should suppress,
        // not repost with the new value.
        AlertCoordinator.evaluate(
            context,
            reading(value = 58, severity = "red", ratePerMinute = 1.5),
            trend(1L, 55, "red"),
        )

        assertEquals("suppressed heartbeat must not repost", firstTitle, redTitle())
        assertTrue("stale content should still show the original value", redTitle()?.contains("55") == true)
    }

    @Test
    fun `low red does not re-fire on a recovery stall within MIN_REALERT_GAP_MS`() {
        AlertCoordinator.evaluate(context, reading(value = 55, severity = "red"), trend(1L, 55, "red"))
        // Recovering - suppressed, but marks wasRecovering=true.
        AlertCoordinator.evaluate(
            context,
            reading(value = 58, severity = "red", ratePerMinute = 1.5),
            trend(1L, 55, "red"),
        )
        // Recovery just stopped (flat now), but the last alert fired only
        // moments ago (well inside MIN_REALERT_GAP_MS) - 2026-08-01: this
        // used to re-fire unconditionally on any stall/reversal, which meant
        // a rate hovering right around zero could retrigger the full-screen
        // takeover every single cycle. Must stay quiet here.
        AlertCoordinator.evaluate(
            context,
            reading(value = 57, severity = "red", ratePerMinute = 0.0),
            trend(1L, 55, "red"),
        )

        assertTrue(
            "must not re-fire on a stall inside MIN_REALERT_GAP_MS",
            redTitle()?.contains("57") != true,
        )
    }

    @Test
    fun `low red re-fires on a recovery stall once MIN_REALERT_GAP_MS has passed`() {
        AlertCoordinator.evaluate(context, reading(value = 55, severity = "red"), trend(1L, 55, "red"))
        AlertCoordinator.evaluate(
            context,
            reading(value = 58, severity = "red", ratePerMinute = 1.5),
            trend(1L, 55, "red"),
        )
        // Backdate the last-fired timestamp past MIN_REALERT_GAP_MS (5 min) -
        // same technique the signal-lost cooldown test below uses, since the
        // code reads plain System.currentTimeMillis().
        context.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE).edit()
            .putLong("last_red_fired_at_ms", System.currentTimeMillis() - 6 * 60_000L)
            .commit()

        AlertCoordinator.evaluate(
            context,
            reading(value = 57, severity = "red", ratePerMinute = 0.0),
            trend(1L, 55, "red"),
        )

        assertTrue(
            "expected a re-fire reflecting the stalled value once past the floor",
            redTitle()?.contains("57") == true,
        )
    }

    @Test
    fun `low red clear hysteresis holds the alert below 80, cancels once past it`() {
        AlertCoordinator.evaluate(context, reading(value = 55, severity = "red"), trend(1L, 55, "red"))
        assertTrue(redTitle() != null)

        // Severity dropped to none, but value (76) is still under the 80
        // clear-hysteresis buffer - must hold the red alert, not cancel it.
        AlertCoordinator.evaluate(context, reading(value = 76, severity = "none"), trend(2L, 76, "none"))
        assertTrue("red alert should still be held under 80", redTitle() != null)

        // Now solidly past the buffer (81) - should actually clear.
        AlertCoordinator.evaluate(context, reading(value = 81, severity = "none"), trend(3L, 81, "none"))
        assertNull("red alert should now be cancelled", redTitle())
    }

    @Test
    fun `high red suppresses repeat alerts during 45-minute management window, re-alerts after cooldown`() {
        AlertCoordinator.evaluate(context, reading(value = 330, severity = "red"), trend(1L, 330, "red"))
        val firstTitle = redTitle()
        assertTrue("initial high red should fire", firstTitle?.contains("330") == true)

        // Fluctuating high (drops to 300 then bumps to 340) inside the 45-min window
        // must NOT re-alarm (prevents alarm fatigue during insulin action).
        AlertCoordinator.evaluate(context, reading(value = 340, severity = "red"), trend(2L, 340, "red"))
        assertEquals("must suppress repeat alerts on fluctuating high within 45m", firstTitle, redTitle())

        // Backdate last fired timestamp past 45 minutes
        context.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE).edit()
            .putLong("last_red_fired_at_ms", System.currentTimeMillis() - 46 * 60_000L)
            .commit()

        AlertCoordinator.evaluate(context, reading(value = 320, severity = "red"), trend(3L, 320, "red"))
        assertTrue("expected high re-alert after 45-minute cooldown elapsed", redTitle()?.contains("320") == true)
    }

    @Test
    fun `high red does not re-fire on a yellow-then-red flap within the cooldown`() {
        // 2026-08-26: real reported case - 351 red, dipped to yellow for two
        // cycles as the rate flattened right at the boundary, then back to red
        // 14 minutes later. Used to force-fire immediately on re-entry; must
        // now fall through to the ordinary 45-minute cooldown instead.
        AlertCoordinator.evaluate(context, reading(value = 351, severity = "red"), trend(1L, 351, "red"))
        val firstTitle = redTitle()
        assertTrue("initial high red should fire", firstTitle?.contains("351") == true)

        AlertCoordinator.evaluate(context, reading(value = 354, severity = "yellow"), trend(2L, 354, "yellow"))
        AlertCoordinator.evaluate(context, reading(value = 352, severity = "yellow"), trend(3L, 352, "yellow"))
        AlertCoordinator.evaluate(context, reading(value = 352, severity = "red"), trend(4L, 352, "red"))

        // The yellow dip cancels the interruptive red notification (existing,
        // unrelated behavior - the persistent status notification still shows
        // the real value throughout); what this fix actually prevents is a
        // BRAND NEW interruptive alert firing for 352 immediately on
        // re-entry, bypassing the 45-minute cooldown. redTitle() must
        // therefore be neither the stale "351" content nor a freshly-posted
        // "352" one - nothing should have force-fired.
        assertTrue(
            "yellow-then-red flap within the cooldown must not force an immediate re-alert",
            redTitle()?.contains("352") != true,
        )
    }

    @Test
    fun `high red still fires immediately on a genuinely fresh episode from in-range`() {
        AlertCoordinator.evaluate(context, reading(value = 150, severity = "none"), trend(1L, 150, "none"))
        AlertCoordinator.evaluate(context, reading(value = 280, severity = "red"), trend(2L, 280, "red"))

        assertTrue("a fresh episode from in-range must still fire immediately", redTitle()?.contains("280") == true)
    }

    @Test
    fun `high red re-alert is held during correction grace while not climbing`() {
        logCorrection(glucoseValue = 310)
        AlertCoordinator.evaluate(context, reading(value = 351, severity = "red"), trend(1L, 351, "red"))
        val firstTitle = redTitle()

        // Backdate past the 45-min cooldown so only the correction grace is
        // being tested here, not the plain cooldown.
        context.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE).edit()
            .putLong("last_red_fired_at_ms", System.currentTimeMillis() - 46 * 60_000L)
            .commit()

        AlertCoordinator.evaluate(
            context,
            reading(value = 352, severity = "red", ratePerMinute = -0.2),
            trend(2L, 352, "red"),
        )

        assertEquals(
            "flat/falling within the 90-minute correction grace must not re-alert",
            firstTitle,
            redTitle(),
        )
    }

    @Test
    fun `high red re-alert fires immediately if still climbing despite a logged correction`() {
        logCorrection(glucoseValue = 310)
        AlertCoordinator.evaluate(context, reading(value = 351, severity = "red"), trend(1L, 351, "red"))

        context.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE).edit()
            .putLong("last_red_fired_at_ms", System.currentTimeMillis() - 46 * 60_000L)
            .commit()

        AlertCoordinator.evaluate(
            context,
            reading(value = 370, severity = "red", ratePerMinute = 1.2),
            trend(2L, 370, "red"),
        )

        assertTrue(
            "still climbing despite a logged correction must alert immediately",
            redTitle()?.contains("370") == true,
        )
    }

    @Test
    fun `high red re-alert resumes once past the 90-minute correction grace`() {
        logCorrection(glucoseValue = 310, timestamp = System.currentTimeMillis() - 91 * 60_000L)
        AlertCoordinator.evaluate(context, reading(value = 351, severity = "red"), trend(1L, 351, "red"))

        context.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE).edit()
            .putLong("last_red_fired_at_ms", System.currentTimeMillis() - 46 * 60_000L)
            .commit()

        AlertCoordinator.evaluate(
            context,
            reading(value = 352, severity = "red", ratePerMinute = -0.2),
            trend(2L, 352, "red"),
        )

        assertTrue(
            "past the 90-minute grace, the plain cooldown heartbeat should resume",
            redTitle()?.contains("352") == true,
        )
    }

    @Test
    fun `low red re-alert is held during 30-minute correction grace while flat`() {
        logCorrection(glucoseValue = 55)
        AlertCoordinator.evaluate(context, reading(value = 55, severity = "red", ratePerMinute = 0.0), trend(1L, 55, "red"))
        val firstTitle = redTitle()

        AlertCoordinator.evaluate(
            context,
            reading(value = 56, severity = "red", ratePerMinute = 0.0),
            trend(1L, 55, "red"),
        )

        assertEquals(
            "flat within the 30-minute low correction grace must not re-alert",
            firstTitle,
            redTitle(),
        )
    }

    @Test
    fun `low red re-alert fires if still falling despite a logged correction`() {
        logCorrection(glucoseValue = 55)
        AlertCoordinator.evaluate(context, reading(value = 55, severity = "red", ratePerMinute = 0.0), trend(1L, 55, "red"))

        // Falling counts as "held state just stopped" (same MIN_REALERT_GAP_MS
        // floor the low-recovery path already uses, see fireRedIfWarranted) -
        // backdate past it so this test isolates the direction check, not the
        // noise floor.
        context.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE).edit()
            .putLong("last_red_fired_at_ms", System.currentTimeMillis() - 6 * 60_000L)
            .commit()

        AlertCoordinator.evaluate(
            context,
            reading(value = 50, severity = "red", ratePerMinute = -1.0),
            trend(1L, 55, "red"),
        )

        assertTrue(
            "still falling despite a logged correction must alert immediately",
            redTitle()?.contains("50") == true,
        )
    }

    @Test
    fun `low red re-alert resumes once past the fixed 30-minute correction grace`() {
        logCorrection(glucoseValue = 55, timestamp = System.currentTimeMillis() - 31 * 60_000L)
        AlertCoordinator.evaluate(context, reading(value = 55, severity = "red", ratePerMinute = 0.0), trend(1L, 55, "red"))

        // Grace already expired, so this is exercising the plain
        // RED_LOW_REALERT_COOLDOWN_MS heartbeat, not the correction grace -
        // backdate past it too.
        context.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE).edit()
            .putLong("last_red_fired_at_ms", System.currentTimeMillis() - 16 * 60_000L)
            .commit()

        AlertCoordinator.evaluate(
            context,
            reading(value = 56, severity = "red", ratePerMinute = 0.0),
            trend(1L, 55, "red"),
        )

        assertTrue(
            "past the fixed 30-minute grace, the plain heartbeat should resume",
            redTitle()?.contains("56") == true,
        )
    }

    @Test
    fun `signal lost fires immediately and re-fires only after the cooldown elapses`() {
        val stale1 = GlucoseDisplayState.Stale(
            lastValue = 90, lastReadingTime = 0L, ageMinutes = 20, lastArrow = GlucoseTrendArrow.FLAT,
        )
        AlertCoordinator.evaluate(context, stale1, null)
        assertTrue(redTitle()?.contains("No new glucose data") == true)
        assertTrue(redTitle()?.contains("20m") == true)

        // Immediately again (same simulated clock) - well inside the 15-min
        // cooldown, must not update the posted content even though ageMinutes
        // has changed.
        val stale2 = stale1.copy(ageMinutes = 21)
        AlertCoordinator.evaluate(context, stale2, null)
        assertTrue("must not repost mid-cooldown", redTitle()?.contains("20m") == true)

        // Simulate the cooldown having elapsed by seeding "last fired at"
        // directly in the past, rather than fast-forwarding a global clock -
        // Robolectric's SystemClock shadow doesn't reliably propagate to
        // plain System.currentTimeMillis() reads, so this is the robust way
        // to test cooldown-elapsed behavior without depending on that.
        context.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE).edit()
            .putLong("signal_lost_last_fired_at_ms", System.currentTimeMillis() - 16 * 60_000L)
            .commit()
        val stale3 = stale1.copy(ageMinutes = 36)
        AlertCoordinator.evaluate(context, stale3, null)
        assertTrue(
            "expected a re-fire reflecting the new age after cooldown, got: ${redTitle()}",
            redTitle()?.contains("36m") == true,
        )
    }

    @Test
    fun `yellow downgrade from red while falling fast does not audibly alert even above 240`() {
        // 2026-08-26, real reported case: 298 red -> 283 yellow, falling
        // -3.1 mg/dL/min - previously force-alerted purely because 283 is
        // still numerically >=240, with no regard for the fact that this was
        // an already-tracked episode improving on its own.
        AlertCoordinator.evaluate(context, reading(value = 298, severity = "red"), trend(1L, 298, "red"))
        val yellowNotificationId = AlertNotifier.YELLOW_ALERT_NOTIFICATION_ID
        context.getSystemService(NotificationManager::class.java).cancel(yellowNotificationId)

        AlertCoordinator.evaluate(
            context,
            reading(value = 283, severity = "yellow", ratePerMinute = -3.1, projected = 238),
            trend(2L, 283, "yellow"),
        )

        assertNull(
            "a red episode improving into yellow while falling fast must not force an audible alert",
            shadowNm.getNotification(yellowNotificationId),
        )
    }

    @Test
    fun `yellow downgrade from red still alerts if not actually falling`() {
        AlertCoordinator.evaluate(context, reading(value = 298, severity = "red"), trend(1L, 298, "red"))
        val yellowNotificationId = AlertNotifier.YELLOW_ALERT_NOTIFICATION_ID
        context.getSystemService(NotificationManager::class.java).cancel(yellowNotificationId)

        // Flat, not falling - the "improving" exception must not apply.
        AlertCoordinator.evaluate(
            context,
            reading(value = 283, severity = "yellow", ratePerMinute = 0.0, projected = 283),
            trend(2L, 283, "yellow"),
        )

        assertTrue(
            "a red->yellow downgrade that isn't actually falling should still alert (still >=240)",
            shadowNm.getNotification(yellowNotificationId) != null,
        )
    }

    @Test
    fun `a fresh yellow episode above 240 still alerts even if already falling`() {
        // No prior red episode to be "improving" from - the exception is
        // specific to a downgrade, not to falling-fast in general.
        AlertCoordinator.evaluate(context, reading(value = 150, severity = "none"), trend(1L, 150, "none"))
        val yellowNotificationId = AlertNotifier.YELLOW_ALERT_NOTIFICATION_ID
        context.getSystemService(NotificationManager::class.java).cancel(yellowNotificationId)

        AlertCoordinator.evaluate(
            context,
            reading(value = 250, severity = "yellow", ratePerMinute = -3.0, projected = 220),
            trend(2L, 250, "yellow"),
        )

        assertTrue(
            "a fresh yellow episode (no prior red) must still alert regardless of direction",
            shadowNm.getNotification(yellowNotificationId) != null,
        )
    }

    @Test
    fun `post-hypo recovery rise inside 40 minutes is suppressed under 240 ceiling`() {
        // Step 1: Caught a low early at 82 mg/dL -> yellow alert fires
        AlertCoordinator.evaluate(context, reading(value = 80, severity = "yellow", ratePerMinute = -1.5), trend(1L, 80, "yellow"))
        
        // Step 2: Treated with juice, now climbing fast (+3.5 mg/dL/min, value 110, projected 157)
        // Inside the 40-minute recovery window, this must NOT fire another yellow alert.
        val yellowNotificationId = AlertNotifier.YELLOW_ALERT_NOTIFICATION_ID
        context.getSystemService(NotificationManager::class.java).cancel(yellowNotificationId)

        AlertCoordinator.evaluate(
            context,
            reading(value = 110, severity = "yellow", ratePerMinute = 3.5, projected = 157),
            trend(2L, 110, "yellow")
        )

        val yellowNotif = shadowNm.getNotification(yellowNotificationId)
        assertNull("expected yellow alert to be suppressed during 40-minute post-hypo recovery", yellowNotif)

        // Step 3: If glucose blows past the 240 ceiling (e.g. 245), alert is allowed
        AlertCoordinator.evaluate(
            context,
            reading(value = 245, severity = "yellow", ratePerMinute = 2.0, projected = 260),
            trend(3L, 245, "yellow")
        )
        val ceilingNotif = shadowNm.getNotification(yellowNotificationId)
        assertTrue("expected alert once crossing the 240 mg/dL recovery ceiling", ceilingNotif != null)
    }
}
