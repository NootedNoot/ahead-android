package com.aheadt1d.app.alerts

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aheadt1d.app.notifications.GlucoseDisplayState
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers CustomThresholdCoordinator.evaluate() itself - the actual code path
 * that filters disabled/expired thresholds, calls CustomThresholdMath, posts
 * (or doesn't post) a notification, and persists the resulting state back to
 * CustomThresholdStore. CustomThresholdMathTest already covers the pure
 * crossing/escalation decision in isolation; this is the "does it actually
 * reach the real store and the real notifier" layer, mirroring
 * PlateauCoordinatorTest's own shape/setup for the same kind of coverage gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomThresholdCoordinatorTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        nm = context.getSystemService(NotificationManager::class.java)
        context.getSharedPreferences("ahead_custom_thresholds", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun reading(value: Int, rate: Double?): GlucoseDisplayState.Reading = GlucoseDisplayState.Reading(
        value = value,
        arrow = GlucoseTrendArrow.fromRatePerMinute(rate),
        readingTime = System.currentTimeMillis(),
        deltaFromPrevious = null,
        trendIsComputed = rate != null,
        severity = null,
        projected = null,
        projectedExtended = null,
        ratePerMinute = rate,
    )

    private fun valueThreshold(
        amount: Double = 300.0,
        enabled: Boolean = true,
        temporary: Boolean = false,
        expiresAtMs: Long? = null,
    ) = CustomThreshold(
        id = CustomThresholdStore.newId(),
        kind = CustomThreshold.Kind.VALUE,
        direction = CustomThreshold.Direction.RISING,
        amount = amount,
        label = "",
        temporary = temporary,
        expiresAtMs = expiresAtMs,
        enabled = enabled,
    )

    @Test
    fun `a disabled threshold never fires even when the value crosses it`() {
        val threshold = valueThreshold(amount = 300.0, enabled = false)
        CustomThresholdStore.add(context, threshold)

        CustomThresholdCoordinator.evaluate(context, reading(value = 350, rate = null))

        val stored = CustomThresholdStore.load(context).single()
        assertEquals(false, stored.currentlyCrossed)
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun `an expired temporary threshold is purged and never evaluated`() {
        val threshold = valueThreshold(amount = 300.0, temporary = true, expiresAtMs = System.currentTimeMillis() - 1000)
        CustomThresholdStore.add(context, threshold)

        CustomThresholdCoordinator.evaluate(context, reading(value = 350, rate = null))

        assertTrue(CustomThresholdStore.load(context).isEmpty())
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun `a fresh crossing posts a notification and persists the fired state`() {
        val threshold = valueThreshold(amount = 300.0)
        CustomThresholdStore.add(context, threshold)

        CustomThresholdCoordinator.evaluate(context, reading(value = 305, rate = null))

        val stored = CustomThresholdStore.load(context).single()
        assertEquals(true, stored.currentlyCrossed)
        assertEquals(305.0, stored.lastFiredAtMetric)
        assertEquals(1, nm.activeNotifications.size)
    }

    @Test
    fun `a value that stays crossed but hasn't moved meaningfully further does not re-notify`() {
        val threshold = valueThreshold(amount = 300.0)
        CustomThresholdStore.add(context, threshold)
        CustomThresholdCoordinator.evaluate(context, reading(value = 305, rate = null))
        assertEquals(1, nm.activeNotifications.size)

        CustomThresholdCoordinator.evaluate(context, reading(value = 310, rate = null)) // +5, under the 20 delta

        assertEquals(1, nm.activeNotifications.size) // no new notification posted
    }

    @Test
    fun `escalating well past the delta fires a second notification`() {
        val threshold = valueThreshold(amount = 300.0)
        CustomThresholdStore.add(context, threshold)
        CustomThresholdCoordinator.evaluate(context, reading(value = 305, rate = null))
        nm.cancelAll()

        CustomThresholdCoordinator.evaluate(context, reading(value = 330, rate = null)) // +25, past the 20 delta

        assertEquals(1, nm.activeNotifications.size)
        val stored = CustomThresholdStore.load(context).single()
        assertEquals(330.0, stored.lastFiredAtMetric)
    }

    @Test
    fun `recovering clearly past the hysteresis buffer cancels the notification and clears the crossed state`() {
        val threshold = valueThreshold(amount = 300.0)
        CustomThresholdStore.add(context, threshold)
        CustomThresholdCoordinator.evaluate(context, reading(value = 305, rate = null))
        assertEquals(1, nm.activeNotifications.size)

        CustomThresholdCoordinator.evaluate(context, reading(value = 270, rate = null)) // clearly below 300-20

        assertEquals(0, nm.activeNotifications.size)
        val stored = CustomThresholdStore.load(context).single()
        assertEquals(false, stored.currentlyCrossed)
    }

    @Test
    fun `a non-Reading display state (stale or no-data) is a no-op`() {
        val threshold = valueThreshold(amount = 300.0)
        CustomThresholdStore.add(context, threshold)

        CustomThresholdCoordinator.evaluate(context, GlucoseDisplayState.NoData)

        val stored = CustomThresholdStore.load(context).single()
        assertEquals(false, stored.currentlyCrossed)
        assertEquals(0, nm.activeNotifications.size)
    }
}
