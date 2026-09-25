package com.aheadt1d.app.alerts

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aheadt1d.app.notifications.GlucoseDisplayState
import com.aheadt1d.app.notifications.GlucoseNotifier
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowNotificationManager
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class AlertNotifierSingleNotificationTest {

    private lateinit var context: Context
    private lateinit var shadowNm: ShadowNotificationManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowNm = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
        shadowNm.allNotifications.clear()
        AlertChannels.ensure(context)
        GlucoseNotifier.createChannel(context)
    }

    @After
    fun tearDown() {
        AlertNotifier.postSecondaryNotificationsForTesting = true
        shadowNm.allNotifications.clear()
    }

    @Test
    fun `production mode disables secondary alert pop-up notification for yellow and red`() {
        AlertNotifier.postSecondaryNotificationsForTesting = false

        AlertNotifier.showYellowAlert(context, value = 132, projected = 105, rate = -1.8, projectedExtended = 78)
        assertNull(
            "Yellow alert must NOT post secondary notification in production mode",
            shadowNm.getNotification(AlertNotifier.YELLOW_ALERT_NOTIFICATION_ID)
        )

        AlertNotifier.showRedAlert(context, value = 73, projected = 40, rate = -2.2)
        assertNull(
            "Red alert must NOT post secondary notification in production mode",
            shadowNm.getNotification(AlertNotifier.RED_ALERT_NOTIFICATION_ID)
        )
    }

    @Test
    fun `main glucose notification displays wait guidance when severity is yellow`() {
        // Falling yellow
        val fallingYellow = GlucoseDisplayState.Reading(
            value = 132,
            arrow = GlucoseTrendArrow.SLOWLY_FALLING,
            ratePerMinute = -1.8,
            deltaFromPrevious = -9,
            readingTime = System.currentTimeMillis(),
            trendIsComputed = true,
            severity = "yellow",
            projected = 105,
            projectedExtended = 78
        )

        val fallingNotification = GlucoseNotifier.buildNotification(context, fallingYellow)
        val textFalling = fallingNotification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertNotNull(textFalling)
        assertTrue(
            "Main notification must guide user to wait for 2nd reading before treating. Got: $textFalling",
            textFalling!!.contains("Wait for 2nd reading before treating")
        )

        // Rising yellow
        val risingYellow = GlucoseDisplayState.Reading(
            value = 185,
            arrow = GlucoseTrendArrow.SLOWLY_RISING,
            ratePerMinute = 1.8,
            deltaFromPrevious = 8,
            readingTime = System.currentTimeMillis(),
            trendIsComputed = true,
            severity = "yellow",
            projected = 210,
            projectedExtended = 235
        )

        val risingNotification = GlucoseNotifier.buildNotification(context, risingYellow)
        val textRising = risingNotification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertNotNull(textRising)
        assertTrue(
            "Main notification must guide user to wait for 2nd reading before correcting. Got: $textRising",
            textRising!!.contains("Wait for 2nd reading before correcting")
        )
    }
}
