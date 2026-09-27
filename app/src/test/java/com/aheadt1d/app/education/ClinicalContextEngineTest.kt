package com.aheadt1d.app.education

import com.aheadt1d.app.events.UserEvent
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.notifications.GlucoseDisplayState
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClinicalContextEngineTest {

    private fun sampleReading(
        sgv: Int = 110,
        rate: Double = 0.0,
        recovering: Boolean = false,
        projected: Int? = null
    ) = GlucoseDisplayState.Reading(
        value = sgv,
        arrow = GlucoseTrendArrow.FLAT,
        readingTime = System.currentTimeMillis(),
        deltaFromPrevious = 0,
        trendIsComputed = true,
        severity = if (sgv <= 70) "red" else if (sgv >= 180) "yellow" else null,
        projected = projected ?: sgv,
        projectedExtended = projected ?: sgv,
        ratePerMinute = rate,
        recoveringFromLow = recovering
    )

    @Test
    fun `sick day event logged within 48h overrides other states with ketone guidance`() {
        val now = System.currentTimeMillis()
        val reading = sampleReading(sgv = 210, rate = 2.0)
        val sickEvent = UserEvent(
            id = 1L,
            timestamp = now - 2 * 60 * 60_000L, // 2h ago
            tag = "illness",
            note = "Feeling under the weather",
            glucoseAtTime = 180f
        )

        val insight = ClinicalContextEngine.evaluate(
            reading = reading,
            recentEvents = listOf(sickEvent),
            nowMillis = now
        )

        assertEquals("sick_day_ketones", insight.id)
        assertTrue(insight.targetUrl.endsWith("#dka"))
        assertTrue(insight.badge.contains("SICK DAY"))
        assertTrue(insight.snippet.contains("ketone"))
    }

    @Test
    fun `sick day detected by note keyword in recent event`() {
        val now = System.currentTimeMillis()
        val reading = sampleReading(sgv = 130)
        val noteEvent = UserEvent(
            id = 2L,
            timestamp = now - 12 * 60 * 60_000L,
            tag = "other",
            note = "High fever and chills today",
            glucoseAtTime = 130f
        )

        val insight = ClinicalContextEngine.evaluate(
            reading = reading,
            recentEvents = listOf(noteEvent),
            nowMillis = now
        )

        assertEquals("sick_day_ketones", insight.id)
        assertTrue(insight.targetUrl.endsWith("#dka"))
    }

    @Test
    fun `active low reading triggers hypoglycemia unawareness and glucagon guidance`() {
        val reading = sampleReading(sgv = 64, rate = -1.2)
        val insight = ClinicalContextEngine.evaluate(reading = reading)

        assertEquals("hypo_unawareness", insight.id)
        assertTrue(insight.targetUrl.endsWith("#hypo"))
        assertEquals("red", insight.severity)
        assertTrue(insight.snippet.contains("alpha-cell glucagon"))
    }

    @Test
    fun `recurrent lows in past week triggers HAAF education even if current BG is in range`() {
        val now = System.currentTimeMillis()
        val reading = sampleReading(sgv = 115, rate = 0.0)
        val recentPoints = listOf(
            GlucosePoint(Instant.ofEpochMilli(now - 1 * 24 * 3600_000L), 62),
            GlucosePoint(Instant.ofEpochMilli(now - 2 * 24 * 3600_000L), 58),
            GlucosePoint(Instant.ofEpochMilli(now - 4 * 24 * 3600_000L), 65)
        )

        val insight = ClinicalContextEngine.evaluate(
            reading = reading,
            recentReadings = recentPoints,
            nowMillis = now
        )

        assertEquals("hypo_unawareness", insight.id)
        assertTrue(insight.targetUrl.endsWith("#hypo"))
        assertTrue(insight.title.contains("Adrenaline Reset"))
        assertTrue(insight.snippet.contains("HAAF"))
    }

    @Test
    fun `rapid rise with elevated BG triggers insulin kinetics stacking card`() {
        val reading = sampleReading(sgv = 175, rate = 2.2)
        val insight = ClinicalContextEngine.evaluate(reading = reading)

        assertEquals("insulin_stacking", insight.id)
        assertTrue(insight.targetUrl.endsWith("#cellular"))
        assertTrue(insight.title.contains("Stacking Trap"))
        assertTrue(insight.snippet.contains("60–90 minutes"))
    }

    @Test
    fun `steep rate change triggers interstitial sensor delay card`() {
        val reading = sampleReading(sgv = 135, rate = -1.8)
        val insight = ClinicalContextEngine.evaluate(reading = reading)

        assertEquals("sensor_lag", insight.id)
        assertTrue(insight.targetUrl.endsWith("#cgm"))
        assertTrue(insight.title.contains("Interstitial Fluid"))
        assertTrue(insight.snippet.contains("5 to 15-minute lag"))
    }

    @Test
    fun `sustained high triggers microvascular glycocalyx card`() {
        val reading = sampleReading(sgv = 225, rate = 0.4)
        val insight = ClinicalContextEngine.evaluate(reading = reading)

        assertEquals("vascular_stress", insight.id)
        assertTrue(insight.targetUrl.endsWith("#complications"))
        assertTrue(insight.title.contains("Endothelial Glycocalyx"))
    }

    @Test
    fun `in range and stable glucose triggers cellular energy balance card`() {
        val reading = sampleReading(sgv = 105, rate = 0.2)
        val insight = ClinicalContextEngine.evaluate(reading = reading)

        assertEquals("cellular_balance", insight.id)
        assertTrue(insight.targetUrl.endsWith("#cellular"))
        assertTrue(insight.title.contains("GLUT4"))
    }
}
