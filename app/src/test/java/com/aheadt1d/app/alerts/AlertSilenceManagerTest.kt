package com.aheadt1d.app.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertSilenceManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AlertSilenceManager.cancelSilence(context)
    }

    @Test
    fun `default state is not silenced`() {
        assertFalse(AlertSilenceManager.isSilenced(context))
        assertFalse(AlertSilenceManager.isPermanentlySilenced(context))
        assertEquals(0, AlertSilenceManager.getRemainingMinutes(context))
        assertEquals("Alerts Active (Normal)", AlertSilenceManager.getSilenceDescription(context))
    }

    @Test
    fun `timed silence sets active silence with positive remaining minutes`() {
        AlertSilenceManager.silence(context, 30)

        assertTrue(AlertSilenceManager.isSilenced(context))
        assertFalse(AlertSilenceManager.isPermanentlySilenced(context))
        val remaining = AlertSilenceManager.getRemainingMinutes(context)
        assertTrue("Remaining minutes should be between 28 and 30, was $remaining", remaining in 28..30)

        val desc = AlertSilenceManager.getSilenceDescription(context)
        assertTrue("Description should mention remaining minutes, was '$desc'", desc.startsWith("Silenced (") && desc.endsWith("m remaining)"))
    }

    @Test
    fun `silenceIndefinitely sets permanent silence with negative one remaining minutes`() {
        AlertSilenceManager.silenceIndefinitely(context)

        assertTrue(AlertSilenceManager.isSilenced(context))
        assertTrue(AlertSilenceManager.isPermanentlySilenced(context))
        assertEquals(-1, AlertSilenceManager.getRemainingMinutes(context))
        assertEquals("Silenced until cancelled", AlertSilenceManager.getSilenceDescription(context))
    }

    @Test
    fun `silence with zero or negative minutes routes to indefinite silence`() {
        AlertSilenceManager.silence(context, 0)
        assertTrue(AlertSilenceManager.isPermanentlySilenced(context))
        assertEquals(-1, AlertSilenceManager.getRemainingMinutes(context))

        AlertSilenceManager.silence(context, -15)
        assertTrue(AlertSilenceManager.isPermanentlySilenced(context))
        assertEquals(-1, AlertSilenceManager.getRemainingMinutes(context))
    }

    @Test
    fun `cancelSilence clears both timed and permanent silence`() {
        AlertSilenceManager.silenceIndefinitely(context)
        assertTrue(AlertSilenceManager.isSilenced(context))

        AlertSilenceManager.cancelSilence(context)
        assertFalse(AlertSilenceManager.isSilenced(context))
        assertFalse(AlertSilenceManager.isPermanentlySilenced(context))
        assertEquals(0, AlertSilenceManager.getRemainingMinutes(context))
        assertEquals("Alerts Active (Normal)", AlertSilenceManager.getSilenceDescription(context))
    }

    @Test
    fun `expired silence is treated as not silenced`() {
        val prefs = context.getSharedPreferences("ahead_alert_silence", Context.MODE_PRIVATE)
        prefs.edit().putLong("silenced_until_epoch_ms", System.currentTimeMillis() - 60_000L).commit()

        assertFalse(AlertSilenceManager.isSilenced(context))
        assertFalse(AlertSilenceManager.isPermanentlySilenced(context))
        assertEquals(0, AlertSilenceManager.getRemainingMinutes(context))
        assertEquals("Alerts Active (Normal)", AlertSilenceManager.getSilenceDescription(context))
    }
}
