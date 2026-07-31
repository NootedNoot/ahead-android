package com.aheadt1d.app.alerts

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression coverage for a real reported bug: dismissing the critical-low
 * takeover screen (CriticalLowSiren.stop()) didn't stop new alerts from
 * firing - the very next check cycle saw the same still-critical value
 * (dismissing a notification doesn't change actual blood sugar) and treated
 * it as a brand-new episode, restarting the whole siren. Fixed via a
 * separate "acknowledged" bit that survives across the active/inactive
 * transition until a genuine recovery clears it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CriticalLowSirenTest {

    private lateinit var context: Context
    private lateinit var shadowNm: org.robolectric.shadows.ShadowNotificationManager

    private fun notificationPosted() = shadowNm.getNotification(2005) != null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.VIBRATE,
        )
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowNm = shadowOf(nm)
        context.getSharedPreferences("ahead_critical_low_siren", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `dismissing stops the siren and does not restart it while still critical`() {
        CriticalLowSiren.check(context, 45)
        assertNotNull("expected the siren to start", shadowNm.getNotification(2005))

        CriticalLowSiren.stop(context) // simulates the takeover screen's dismiss button

        // Same still-critical value comes through again on the very next
        // check cycle (dismissing didn't change actual blood sugar) - this
        // must NOT be read as a new episode.
        CriticalLowSiren.check(context, 45)
        assertNull("dismissing must prevent an immediate restart on the same episode", shadowNm.getNotification(2005))
    }

    @Test
    fun `a real recovery clears the acknowledged episode so a later drop fires fresh`() {
        CriticalLowSiren.check(context, 45)
        CriticalLowSiren.stop(context)
        CriticalLowSiren.check(context, 45)
        assertNull(shadowNm.getNotification(2005)) // still suppressed, per the test above

        // Genuine recovery.
        CriticalLowSiren.check(context, 90)
        assertNull(shadowNm.getNotification(2005))

        // A fresh drop after a real recovery is a NEW episode - must fire.
        CriticalLowSiren.check(context, 48)
        assertNotNull("a fresh critical episode after recovery must fire, not stay suppressed", shadowNm.getNotification(2005))
    }

    @Test
    fun `never-dismissed episode keeps re-checking normally (no false suppression)`() {
        CriticalLowSiren.check(context, 45)
        assertNotNull(shadowNm.getNotification(2005))
        // Repeated check() calls without a dismiss must not suppress anything -
        // isActive() stays true, so this exercises the heartbeat path, not
        // the acknowledged path.
        CriticalLowSiren.check(context, 44)
        assertNotNull(shadowNm.getNotification(2005))
    }
}
