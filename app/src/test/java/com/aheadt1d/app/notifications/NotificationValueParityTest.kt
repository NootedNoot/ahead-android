package com.aheadt1d.app.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aheadt1d.app.alerts.AlertChannels
import com.aheadt1d.app.alerts.AlertCoordinator
import com.aheadt1d.app.alerts.AlertExplainer
import com.aheadt1d.app.alerts.AlertNotifier
import com.aheadt1d.app.alerts.AlertTones
import com.aheadt1d.app.alerts.isLowSideYellow
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.state.DebugGlucoseOverride
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.state.RawReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationValueParityTest {

    private lateinit var context: Context
    private lateinit var shadowNm: org.robolectric.shadows.ShadowNotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowNm = shadowOf(nm)
        context.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ahead_alert_channels", Context.MODE_PRIVATE).edit().clear().commit()
        LatestTrendRepository.clear(context)
        DebugGlucoseOverride.clear()
    }

    @Test
    fun `ongoing notification title and text match reading value and projections exactly`() {
        val reading = GlucoseDisplayState.Reading(
            value = 142,
            arrow = GlucoseTrendArrow.SLOWLY_FALLING,
            readingTime = 1773000000000L,
            deltaFromPrevious = -4,
            trendIsComputed = true,
            severity = "none",
            projected = 136,
            projectedExtended = 130,
            ratePerMinute = -0.4
        )

        GlucoseNotifier.createChannel(context)
        val notification = GlucoseNotifier.buildNotification(context, reading)

        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        assertNotNull(title)
        assertNotNull(text)
        assertTrue("Title must contain current value 142, got: $title", title!!.contains("142"))
        assertTrue("Text must contain 15m projection 136, got: $text", text!!.contains("136"))
        assertTrue("Text must contain 30m projection 130, got: $text", text.contains("130"))
    }

    @Test
    fun `yellow alert notification title and explainer match reading value and projection`() {
        AlertChannels.ensure(context)
        AlertNotifier.showYellowAlert(context, value = 85, projected = 75, rate = -1.5, projectedExtended = 65)

        val notification = shadowNm.getNotification(AlertNotifier.YELLOW_ALERT_NOTIFICATION_ID)
        assertNotNull(notification)

        val title = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        assertNotNull(title)
        assertNotNull(text)
        assertTrue("Title must contain current value 85, got: $title", title!!.contains("85"))
        assertTrue("Text must contain explanation with value 85, got: $text", text!!.contains("85"))
        assertTrue("Text must contain projected value 75, got: $text", text.contains("75"))
    }

    @Test
    fun `yellow low caution routes to WARN_LOW and high caution to WARN_HIGH`() {
        // Hypo caution: 85 dropping to 75
        assertTrue("85 dropping to 75 is low side yellow", isLowSideYellow(value = 85, projected = 75))

        // Hypo caution: 78 with no projection yet
        assertTrue("78 is low side yellow", isLowSideYellow(value = 78, projected = null))

        // Hyper caution: 150 rising to 172
        assertFalse("150 rising to 172 is high side yellow", isLowSideYellow(value = 150, projected = 172))

        // Hyper caution: 185 with no projection yet
        assertFalse("185 is high side yellow", isLowSideYellow(value = 185, projected = null))
    }

    @Test
    fun `spoken projection window aligns with AlertExplainer chosen window`() {
        // Near-term delta is small (192 -> 194 is delta 2 < 5), extended delta moves further (192 -> 205 is delta 13)
        val (window, valChosen) = AlertExplainer.pickProjectionWindow(currentValue = 192, projected = 194, projectedExtended = 205)
        assertEquals("Must choose 30m window when near move is unremarkable", 30, window)
        assertEquals("Must choose extended value 205", 205, valChosen)

        val oneLiner = AlertExplainer.oneLiner(currentValue = 192, rate = 0.4, projected = 194, projectedExtended = 205)
        assertTrue("Explainer should cite 30 min window, got: $oneLiner", oneLiner.contains("205 in 30 min"))
    }

    @Test
    fun `AlertCoordinator advances alert state with readingTime when offline or backend is null`() {
        val timestamp1 = 1773000000000L
        val reading1 = GlucoseDisplayState.Reading(
            value = 150,
            arrow = GlucoseTrendArrow.FLAT,
            readingTime = timestamp1,
            deltaFromPrevious = null,
            trendIsComputed = true,
            severity = "none",
            projected = 150,
            projectedExtended = 150,
            ratePerMinute = 0.0
        )

        // Initial in-range reading with null trend (backend offline)
        AlertCoordinator.evaluate(context, reading1, trend = null)
        val prefs = context.getSharedPreferences("ahead_alert_state", Context.MODE_PRIVATE)
        assertEquals("none", prefs.getString("last_handled_severity", null))
        assertEquals(timestamp1, prefs.getLong("last_handled_trend_date", 0L))

        // Second reading: climbs to yellow (190), still offline (trend = null)
        val timestamp2 = timestamp1 + 300_000L
        val reading2 = GlucoseDisplayState.Reading(
            value = 190,
            arrow = GlucoseTrendArrow.UP,
            readingTime = timestamp2,
            deltaFromPrevious = 40,
            trendIsComputed = true,
            severity = "yellow",
            projected = 210,
            projectedExtended = 230,
            ratePerMinute = 2.0
        )

        AlertCoordinator.evaluate(context, reading2, trend = null)
        assertEquals("yellow", prefs.getString("last_handled_severity", null))
        assertEquals(timestamp2, prefs.getLong("last_handled_trend_date", 0L))

        // Verify yellow alert notification was posted
        val yellowNotif = shadowNm.getNotification(AlertNotifier.YELLOW_ALERT_NOTIFICATION_ID)
        assertNotNull("Yellow alert must post even when trend is null", yellowNotif)
    }

    @Test
    fun `RawReading fromPoints populates all rate math fields consistently`() {
        val now = Instant.now()
        val points = listOf(
            GlucosePoint(now.minusSeconds(900), 100),
            GlucosePoint(now.minusSeconds(600), 90),
            GlucosePoint(now.minusSeconds(300), 82),
            GlucosePoint(now, 75)
        )

        val raw = RawReading.fromPoints(points)
        assertNotNull(raw)
        assertEquals(75, raw!!.value)
        assertEquals(now.toEpochMilli(), raw.time)
        assertNotNull(raw.ratePerMinute)
        assertNotNull(raw.deltaFromPrevious)
        assertEquals(-7, raw.deltaFromPrevious)
        assertEquals(3, raw.recentRates.size)
        assertNotNull(raw.severityRatePerMinute)
        assertTrue(raw.recoveringFromLow) // dropped <= 80
        assertNotNull(raw.excursionDurationMinutes)
    }

    @Test
    fun `injected test data adds disclaimer and prefixes to alert notifications and ongoing notification`() {
        DebugGlucoseOverride.setPoints(listOf(GlucosePoint(Instant.now(), 58)))
        assertTrue(DebugGlucoseOverride.isActive)

        AlertChannels.ensure(context)
        AlertNotifier.showRedAlert(context, value = 58, projected = 48, rate = -2.5)

        val redNotif = shadowNm.getNotification(AlertNotifier.RED_ALERT_NOTIFICATION_ID)
        assertNotNull(redNotif)
        val redTitle = redNotif?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val redText = redNotif?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val redSubText = redNotif?.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val redBigText = redNotif?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        assertTrue(redTitle!!.startsWith("[INJECTED]"))
        assertTrue(redText!!.startsWith("[INJECTED TEST DATA]"))
        assertEquals(DebugGlucoseOverride.DISCLAIMER_SHORT, redSubText)
        assertTrue(redBigText!!.contains(DebugGlucoseOverride.DISCLAIMER))

        // Ongoing notification with injected test data active
        val reading = GlucoseDisplayState.Reading(
            value = 58,
            arrow = GlucoseTrendArrow.DOWN,
            readingTime = 1773000000000L,
            deltaFromPrevious = -8,
            trendIsComputed = true,
            severity = "red",
            projected = 48,
            projectedExtended = 40,
            ratePerMinute = -2.5
        )
        val ongoingNotif = GlucoseNotifier.buildNotification(context, reading)
        val ongoingTitle = ongoingNotif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val ongoingText = ongoingNotif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val ongoingSubText = ongoingNotif.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        assertTrue(ongoingTitle!!.startsWith("[INJECTED]"))
        assertTrue(ongoingText!!.startsWith("[INJECTED TEST DATA]"))
        assertEquals(DebugGlucoseOverride.DISCLAIMER_SHORT, ongoingSubText)

        // Clear injected data and verify normal behavior
        DebugGlucoseOverride.clear()
        assertFalse(DebugGlucoseOverride.isActive)

        val cleanNotif = GlucoseNotifier.buildNotification(context, reading)
        val cleanTitle = cleanNotif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val cleanSubText = cleanNotif.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        assertFalse(cleanTitle!!.contains("[INJECTED]"))
        assertEquals(null, cleanSubText)
    }

    @Test
    fun `direct forced alert button with isInjected true includes injected disclaimer`() {
        DebugGlucoseOverride.clear()
        assertFalse(DebugGlucoseOverride.isActive)

        AlertChannels.ensure(context)
        AlertNotifier.showYellowAlert(context, value = 85, projected = 75, rate = -1.5, projectedExtended = 65, isInjected = true)

        val yellowNotif = shadowNm.getNotification(AlertNotifier.YELLOW_ALERT_NOTIFICATION_ID)
        assertNotNull(yellowNotif)
        val title = yellowNotif?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = yellowNotif?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = yellowNotif?.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val bigText = yellowNotif?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        assertTrue(title!!.startsWith("[INJECTED]"))
        assertTrue(text!!.startsWith("[INJECTED TEST DATA]"))
        assertEquals(DebugGlucoseOverride.DISCLAIMER_SHORT, subText)
        assertTrue(bigText!!.contains(DebugGlucoseOverride.DISCLAIMER))
    }
}

