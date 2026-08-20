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
    fun `low red clear hysteresis holds the alert below 75, cancels once past it`() {
        AlertCoordinator.evaluate(context, reading(value = 55, severity = "red"), trend(1L, 55, "red"))
        assertTrue(redTitle() != null)

        // Severity dropped to none, but value (65) is still under the 75
        // clear-hysteresis buffer - must hold the red alert, not cancel it.
        AlertCoordinator.evaluate(context, reading(value = 65, severity = "none"), trend(2L, 65, "none"))
        assertTrue("red alert should still be held", redTitle() != null)

        // Now solidly past the buffer - should actually clear.
        AlertCoordinator.evaluate(context, reading(value = 80, severity = "none"), trend(3L, 80, "none"))
        assertNull("red alert should now be cancelled", redTitle())
    }

    @Test
    fun `high red re-arms on a real move toward danger past the peak`() {
        AlertCoordinator.evaluate(context, reading(value = 260, severity = "red"), trend(1L, 260, "red"))
        assertTrue(redTitle()?.contains("260") == true)

        // Same severity+date heartbeat, still well inside cooldown, but a
        // genuine new peak (>= RED_HIGH_REARM_THRESHOLD_MGDL past the old one).
        AlertCoordinator.evaluate(context, reading(value = 278, severity = "red"), trend(1L, 260, "red"))
        assertTrue("expected a re-fire on a material new peak", redTitle()?.contains("278") == true)
    }

    @Test
    fun `high red heartbeat with only noise-level movement does not repost`() {
        AlertCoordinator.evaluate(context, reading(value = 260, severity = "red"), trend(1L, 260, "red"))
        val firstTitle = redTitle()

        // +3 mg/dL is below RED_HIGH_REARM_THRESHOLD_MGDL (15) - ordinary
        // sensor noise, must not re-fire mid-cooldown.
        AlertCoordinator.evaluate(context, reading(value = 263, severity = "red"), trend(1L, 260, "red"))

        assertEquals(firstTitle, redTitle())
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
}
