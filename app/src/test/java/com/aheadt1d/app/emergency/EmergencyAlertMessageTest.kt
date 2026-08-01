package com.aheadt1d.app.emergency

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The emergency SMS is the only part of this app a person other than the
 * diabetic ever reads, and they read it while deciding whether to drop what
 * they're doing. The 2026-08-01 audit found it quoted only the current
 * glucose value, so a genuine crash caught early ("103 and falling at
 * -3.7/min, heading for 48") went out as "glucose is 103 mg/dL - low" -
 * a number the recipient would reasonably dismiss as a false alarm.
 *
 * These tests pin the two things that make it legible: how fast, and where
 * it's heading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmergencyAlertMessageTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EmergencyContactsPrefs.setUserName(context, "Ryan")
    }

    private fun lowMessage(value: Int, rate: Double?, projected: Int?) =
        EmergencyAlertRepository.messageFor(
            context, EmergencyAlertType.LOW, value, rate,
            minutesUnacknowledged = 15L, projected = projected,
        )

    @Test
    fun `a normal-looking value still reads as urgent when it is crashing`() {
        // Ryan's real reading from the day this was written.
        val message = lowMessage(103, rate = -3.7, projected = 48)
        assertTrue("must name the current value", message.contains("103 mg/dL"))
        assertTrue("must convey speed", message.contains("dropping very fast"))
        assertTrue("must show the numeric rate", message.contains("-3.7 mg/dL/min"))
        assertTrue("must show where it's heading", message.contains("heading for 48 mg/dL"))
    }

    @Test
    fun `speed wording distinguishes a mild drift from a crash`() {
        assertTrue(lowMessage(65, rate = -1.3, projected = 55).contains("and dropping ("))
        assertTrue(lowMessage(65, rate = -2.4, projected = 45).contains("dropping fast"))
        assertTrue(lowMessage(65, rate = -3.2, projected = 40).contains("dropping very fast"))
    }

    @Test
    fun `a projection heading back toward safe is omitted rather than undercutting the alert`() {
        // Still low, but climbing - quoting "heading for 68" would read as
        // reassurance in a message whose whole job is to get someone moving.
        val message = lowMessage(58, rate = 1.5, projected = 68)
        assertFalse(message.contains("heading for"))
        assertTrue("rising is still worth stating plainly", message.contains("rising"))
    }

    @Test
    fun `missing rate and projection degrade gracefully`() {
        val message = lowMessage(48, rate = null, projected = null)
        assertTrue(message.contains("48 mg/dL"))
        assertTrue(message.contains("low"))
        assertFalse(message.contains("heading for"))
        assertFalse(message.contains("null"))
    }

    @Test
    fun `high-side alerts mirror the same treatment`() {
        val message = EmergencyAlertRepository.messageFor(
            context, EmergencyAlertType.HIGH, 280, rate = 2.6,
            minutesUnacknowledged = 15L, projected = 330,
        )
        assertTrue(message.contains("280 mg/dL"))
        assertTrue(message.contains("rising fast"))
        assertTrue(message.contains("heading for 330 mg/dL"))
    }
}
