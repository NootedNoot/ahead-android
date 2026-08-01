package com.aheadt1d.app.alerts

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ===================== Two-band (tanking/emergency) coverage =====================
    // Added 2026-08-01 after a direct ask: hypoglycemia unawareness means no
    // bodily warning below ~55, so a value that's still above the hard floor
    // but genuinely falling deserves the same can't-miss delivery, just not
    // the same nonstop-repeat urgency as a true emergency (that would be
    // alarm fatigue for routine, easily-handled lower-side readings).

    private fun sirenPrefs() = context.getSharedPreferences("ahead_critical_low_siren", Context.MODE_PRIVATE)

    @Test
    fun `falling through the band starts a tanking episode and fires one alert`() {
        CriticalLowSiren.check(context, 66, rate = -1.5)
        assertNotNull("tanking should still post the notification", shadowNm.getNotification(2005))
        assertEquals("tanking", sirenPrefs().getString("band", null))
        assertTrue(sirenPrefs().getBoolean("active", false))
    }

    @Test
    fun `flat or rising in the tanking band never starts anything`() {
        CriticalLowSiren.check(context, 66, rate = 0.5)
        assertNull("flat isn't tanking", shadowNm.getNotification(2005))
        CriticalLowSiren.check(context, 66, rate = null)
        assertNull("unknown rate can't be tanking", shadowNm.getNotification(2005))
    }

    @Test
    fun `tanking episode suppresses re-alert while genuinely recovering`() {
        CriticalLowSiren.check(context, 66, rate = -1.5)
        val firedAt = sirenPrefs().getLong("tanking_last_fired_at_ms", -1L)

        // Rising, and NOT into a deeper rung - must stay quiet.
        CriticalLowSiren.check(context, 68, rate = 0.8)
        assertEquals("must not re-fire while recovering", firedAt, sirenPrefs().getLong("tanking_last_fired_at_ms", -1L))
    }

    @Test
    fun `tanking episode re-fires immediately once recovery stalls`() {
        CriticalLowSiren.check(context, 66, rate = -1.5) // starts, fires with value=66
        CriticalLowSiren.check(context, 66, rate = 0.8) // recovering, same rung - suppressed
        assertEquals("recovering call must not have re-fired", 66, sirenPrefs().getInt("value", -1))

        // Recovery stalled. Same rung as the opening value (67), so this is
        // the stall rule firing, not the ladder.
        CriticalLowSiren.check(context, 65, rate = -0.3)
        assertEquals("must re-fire immediately once recovery stalls, not wait for the cooldown", 65, sirenPrefs().getInt("value", -1))
    }

    // ===================== Descending alert ladder =====================
    // The owner's framing: a stepped low-battery warning, but for glucose.
    // One ping on the way down is a single point of failure when the person
    // has no bodily hypo symptoms to fall back on.

    @Test
    fun `each ladder rung fires once on the way down`() {
        CriticalLowSiren.check(context, 72, rate = -2.0) // opens at the 73 rung
        assertEquals(73, sirenPrefs().getInt("deepest_rung_fired", -1))

        CriticalLowSiren.check(context, 69, rate = -2.0) // crosses 70
        assertEquals(70, sirenPrefs().getInt("deepest_rung_fired", -1))
        assertEquals(69, sirenPrefs().getInt("value", -1))

        CriticalLowSiren.check(context, 66, rate = -2.0) // crosses 67
        assertEquals(67, sirenPrefs().getInt("deepest_rung_fired", -1))

        CriticalLowSiren.check(context, 62, rate = -2.0) // crosses 63
        assertEquals(63, sirenPrefs().getInt("deepest_rung_fired", -1))
    }

    @Test
    fun `climbing back up through rungs never re-fires`() {
        CriticalLowSiren.check(context, 62, rate = -2.0) // opens straight at the 63 rung
        assertEquals(63, sirenPrefs().getInt("deepest_rung_fired", -1))
        val firedAt = sirenPrefs().getLong("tanking_last_fired_at_ms", -1L)

        CriticalLowSiren.check(context, 66, rate = 1.5) // back up through 67
        CriticalLowSiren.check(context, 69, rate = 1.5) // back up through 70
        assertEquals("climbing must stay quiet", firedAt, sirenPrefs().getLong("tanking_last_fired_at_ms", -1L))
        assertEquals("low-water mark must not move back up", 63, sirenPrefs().getInt("deepest_rung_fired", -1))
    }

    @Test
    fun `opening partway down does not replay the rungs above it`() {
        CriticalLowSiren.check(context, 64, rate = -2.0)
        // 64 sits below 73/70/67, but only 67 is the deepest actually reached -
        // the ones above were never announced and must not be replayed.
        assertEquals(67, sirenPrefs().getInt("deepest_rung_fired", -1))
    }

    @Test
    fun `a value above the top rung opens nothing`() {
        CriticalLowSiren.check(context, 74, rate = -3.0)
        assertNull("74 is above the ladder entirely", shadowNm.getNotification(2005))
    }

    @Test
    fun `recovery threshold is 75, not 70`() {
        CriticalLowSiren.check(context, 66, rate = -1.5)
        assertTrue(sirenPrefs().getBoolean("active", false))

        CriticalLowSiren.check(context, 72, rate = 1.5) // above the old 70, still in the danger band
        assertTrue("72 must NOT count as recovered", sirenPrefs().getBoolean("active", false))

        CriticalLowSiren.check(context, 75, rate = 1.5)
        assertFalse("75 ends the episode", sirenPrefs().getBoolean("active", false))
    }

    @Test
    fun `tanking episode escalates to emergency band on crossing the hard floor`() {
        CriticalLowSiren.check(context, 66, rate = -2.5)
        assertEquals("tanking", sirenPrefs().getString("band", null))

        CriticalLowSiren.check(context, 52, rate = -3.0)
        assertEquals("must escalate once truly critical", "emergency", sirenPrefs().getString("band", null))
    }

    @Test
    fun `emergency episode de-escalates to tanking on partial recovery above the hard floor`() {
        CriticalLowSiren.check(context, 48, rate = -3.0)
        assertEquals("emergency", sirenPrefs().getString("band", null))

        CriticalLowSiren.check(context, 60, rate = -1.0) // above 55, still under the 70 recovery threshold
        assertEquals("must de-escalate, not keep nonstop-repeating", "tanking", sirenPrefs().getString("band", null))
    }

    @Test
    fun `full recovery from a tanking episode clears the acknowledged state like emergency does`() {
        CriticalLowSiren.check(context, 66, rate = -1.5)
        CriticalLowSiren.stop(context)
        CriticalLowSiren.check(context, 66, rate = -1.5)
        assertNull("dismissed tanking episode must not immediately restart", shadowNm.getNotification(2005))

        CriticalLowSiren.check(context, 90, rate = 1.0) // genuine recovery
        CriticalLowSiren.check(context, 66, rate = -1.5) // fresh episode after recovery
        assertNotNull("a fresh tanking episode after recovery must fire, not stay suppressed", shadowNm.getNotification(2005))
    }

    /**
     * THE regression test for the worst bug the 2026-08-01 audit found.
     *
     * Dismissing a tanking warning set an "acknowledged" flag that was only
     * ever cleared by recovering to the recovery threshold - and the
     * acknowledgment gate ran BEFORE the critical-low check. So dismissing at
     * 65 ("yes, I know I'm dropping") silenced the emergency siren the whole
     * way down: 60, 55, 48, 42, all the way to unconsciousness, in total
     * silence, for the one person whose body gives them no warning of its own.
     *
     * Acknowledging "I'm dropping" is not consent to silence at 45.
     */
    @Test
    fun `dismissing a tanking warning must NOT suppress the emergency siren`() {
        CriticalLowSiren.check(context, 65, rate = -2.0)
        assertNotNull("tanking alert should have fired", shadowNm.getNotification(2005))
        assertEquals("tanking", sirenPrefs().getString("band", null))

        CriticalLowSiren.stop(context) // user swipes it away: "I know, I'm on it"
        assertNull(shadowNm.getNotification(2005))

        // Still dropping, now genuinely critical.
        CriticalLowSiren.check(context, 45, rate = -2.0)

        assertNotNull(
            "a true critical low MUST override a dismissed tanking warning",
            shadowNm.getNotification(2005),
        )
        assertEquals("emergency", sirenPrefs().getString("band", null))
        assertTrue(sirenPrefs().getBoolean("active", false))
    }

    @Test
    fun `dismissing the emergency siren still suppresses re-fire at the same tier`() {
        // The inverse of the test above, and deliberately different: there is
        // nothing worse to escalate to, and re-nagging someone actively
        // treating a 45 is exactly the alarm fatigue this tier can least
        // afford.
        CriticalLowSiren.check(context, 45, rate = -2.0)
        CriticalLowSiren.stop(context)
        CriticalLowSiren.check(context, 42, rate = -2.0)
        assertNull("acknowledged emergency must stay quiet until real recovery", shadowNm.getNotification(2005))
    }
}
