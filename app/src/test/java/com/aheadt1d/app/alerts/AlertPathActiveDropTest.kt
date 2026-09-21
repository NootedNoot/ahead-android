package com.aheadt1d.app.alerts

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aheadt1d.app.notifications.GlucoseDisplayState
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Item 4 Audit Test: Sensor Data Loss ("Silence as All-Clear")
 *
 * Verifies that when sensor packets stop arriving while glucose is in an active downward drop
 * (e.g. arrow is DOWN, DOUBLE_DOWN, or SLOWLY_FALLING), Ahead escalates the signal-lost alert
 * from a generic "No new glucose data" notice to a critical "Signal lost while dropping" emergency alert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertPathActiveDropTest {

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
    }

    private fun titleOf(id: Int): String? =
        shadowNm.getNotification(id)?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun textOf(id: Int): String? =
        shadowNm.getNotification(id)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun `stale alert while stable shows standard signal lost copy`() {
        val staleState = GlucoseDisplayState.Stale(
            lastValue = 110,
            lastReadingTime = System.currentTimeMillis() - 15 * 60_000L,
            ageMinutes = 15,
            lastArrow = GlucoseTrendArrow.FLAT
        )

        AlertCoordinator.evaluate(context, staleState, null)

        val title = titleOf(AlertNotifier.RED_ALERT_NOTIFICATION_ID)
        assertNotNull("Signal lost alert must post", title)
        assertTrue("Expected standard signal lost title, got: $title", title?.contains("No new glucose data") == true)
    }

    @Test
    fun `stale alert during an active drop escalates to critical dropping warning`() {
        val droppingStale = GlucoseDisplayState.Stale(
            lastValue = 85,
            lastReadingTime = System.currentTimeMillis() - 15 * 60_000L,
            ageMinutes = 15,
            lastArrow = GlucoseTrendArrow.DOWN
        )

        AlertCoordinator.evaluate(context, droppingStale, null)

        val title = titleOf(AlertNotifier.RED_ALERT_NOTIFICATION_ID)
        assertNotNull("Signal lost alert must post", title)
        assertTrue(
            "Expected critical dropping title to alert user to immediate danger, got: $title",
            title?.contains("Signal lost while dropping") == true
        )

        val text = textOf(AlertNotifier.RED_ALERT_NOTIFICATION_ID)
        assertTrue(
            "Expected guidance instructing immediate fingerstick / glucose check, got: $text",
            text?.contains("Check blood sugar immediately") == true || text?.contains("Check glucose immediately") == true
        )
    }

    @Test
    fun `losing all data via NoData after an active drop preserves the drop arrow and escalates`() {
        val droppingReading = GlucoseDisplayState.Reading(
            value = 85,
            arrow = GlucoseTrendArrow.DOWN,
            readingTime = System.currentTimeMillis() - 20 * 60_000L,
            deltaFromPrevious = -3,
            trendIsComputed = true,
            severity = "yellow",
            ratePerMinute = -1.5,
            projected = 62,
            projectedExtended = 40,
        )

        // First handle the dropping reading
        AlertCoordinator.evaluate(context, droppingReading, null)
        nm.cancelAll()

        // Now sensor data is completely lost (NoData)
        AlertCoordinator.evaluate(context, GlucoseDisplayState.NoData, null)

        val title = titleOf(AlertNotifier.RED_ALERT_NOTIFICATION_ID)
        assertNotNull("Signal lost alert must post on NoData", title)
        assertTrue(
            "Expected critical dropping title after NoData following a drop, got: $title",
            title?.contains("Signal lost while dropping") == true
        )
    }

    @Test
    fun `dropping signal lost re-alerts after 10 minutes rather than waiting 15 minutes`() {
        val droppingStale = GlucoseDisplayState.Stale(
            lastValue = 85,
            lastReadingTime = System.currentTimeMillis() - 15 * 60_000L,
            ageMinutes = 15,
            lastArrow = GlucoseTrendArrow.DOWN
        )

        // First fire
        AlertCoordinator.evaluate(context, droppingStale, null)
        assertNotNull("Initial signal lost must post", titleOf(AlertNotifier.RED_ALERT_NOTIFICATION_ID))
        nm.cancelAll()

        // 11 minutes pass (less than standard 15m cooldown, but past 10m dropping cooldown)
        val prefs = context.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE)
        val initialFiredAt = prefs.getLong("signal_lost_last_fired_at_ms", 0L)
        prefs.edit().putLong("signal_lost_last_fired_at_ms", initialFiredAt - 11 * 60_000L).commit()

        AlertCoordinator.evaluate(context, droppingStale.copy(ageMinutes = 26), null)

        val title = titleOf(AlertNotifier.RED_ALERT_NOTIFICATION_ID)
        assertNotNull("Dropping blackout must re-alert after 10 minutes", title)
        assertTrue(title?.contains("Signal lost while dropping") == true)
    }
}

