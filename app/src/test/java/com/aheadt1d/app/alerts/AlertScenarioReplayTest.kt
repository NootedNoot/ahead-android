package com.aheadt1d.app.alerts

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.notifications.GlucoseDisplayState
import com.aheadt1d.app.notifications.toDisplayState
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.state.RawReading
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
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * End-to-end scenario replays (2026-09-23): a 5-minute-cadence series goes through the exact real
 * on-device chain - GlucosePoint -> RawReading.fromPoints -> toDisplayState -> SeverityEngine ->
 * AlertCoordinator.evaluate, with PlateauCoordinator.evaluate run every cycle like
 * GlucoseCheckRunner does - with the clock driven in step, so cooldowns and grace windows behave
 * like real time. Asserts what a person would actually experience.
 *
 * The headline case is the owner's REAL trace from 2026-09-22 20:07-23:37 (pulled from the backend's
 * readings table): a 217 -> 75 crash, a bounce 79 -> 87 -> 89 -> 83 -> 74 -> 68, then a second
 * bounce 96 -> 74. Before the 2026-09-23 fixes the 74 at 22:27 (projected 47) scored YELLOW.
 *
 * A readable per-reading table for every scenario is written to app/build/scenario-replay/.
 */
@RunWith(RobolectricTestRunner::class)
// instrumentedPackages: Robolectric only redirects System.currentTimeMillis() to its controllable
// clock inside classes it instruments, and app packages aren't instrumented by default - without
// this, AlertCoordinator's cooldowns read the REAL wall clock and every replayed reading looks
// stale. `clock control works as seen by app code` guards this.
@Config(sdk = [34], instrumentedPackages = ["com.aheadt1d.app", "org.aheadt1d.ratemath"])
class AlertScenarioReplayTest {

    private lateinit var context: Context
    private lateinit var shadowNm: org.robolectric.shadows.ShadowNotificationManager
    private val base = 1_790_000_000_000L

    data class Step(
        val index: Int,
        val sgv: Int,
        val severity: String,
        val projected: Int?,
        val redTitle: String?,
        val redPostedAudibly: Boolean,
        val yellowPostedAudibly: Boolean,
        val anyAudible: Boolean,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowNm = shadowOf(context.getSystemService(NotificationManager::class.java))
        for (p in listOf("ahead_alert_state", "ahead_alert_channels", "ahead_plateau_state", "ahead_alert_silence")) {
            context.getSharedPreferences(p, Context.MODE_PRIVATE).edit().clear().commit()
        }
        LatestTrendRepository.clear(context)
        SystemClock.setCurrentTimeMillis(base)
    }

    @Test
    fun `clock control works as seen by app code`() {
        val reading = RawReading(value = 100, time = base, ratePerMinute = null, deltaFromPrevious = null)
        assertEquals(0L, com.aheadt1d.app.state.minutesSinceReading(reading))
        SystemClock.setCurrentTimeMillis(base + 47 * 60_000L)
        assertEquals(47L, com.aheadt1d.app.state.minutesSinceReading(reading))
    }

    private fun title(n: Notification?): String? = n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun slotEvent(label: String, before: Notification?, after: Notification?): String {
        if (before === after) return ""
        if (after == null) return "[$label cleared] "
        val audible = after.channelId != AlertChannels.QUIET_CHANNEL_ID
        return "[$label ${if (audible) "AUDIBLE" else "silent"}: ${title(after)}] "
    }

    private fun postedAudibly(before: Notification?, after: Notification?) =
        after != null && after !== before && after.channelId != AlertChannels.QUIET_CHANNEL_ID

    /** Correction entries: index -> explicitLow (true = juice, false = insulin). */
    private fun replay(name: String, values: List<Int>, corrections: Map<Int, Boolean> = emptyMap()): List<Step> {
        val out = StringBuilder("=== $name ===\n min  sgv   rate  p15  p30  sev     events\n")
        val points = mutableListOf<GlucosePoint>()
        val steps = mutableListOf<Step>()
        var prevRed: Notification? = null
        var prevYellow: Notification? = null
        for ((i, v) in values.withIndex()) {
            val t = base + i * 5 * 60_000L
            SystemClock.setCurrentTimeMillis(t + 30_000L)
            points.add(GlucosePoint(Instant.ofEpochMilli(t), v))
            val raw = RawReading.fromPoints(points.filter { t - it.time.toEpochMilli() <= 120 * 60_000L })!!
            LatestTrendRepository.updateRawReading(context, raw)
            corrections[i]?.let { PlateauCoordinator.onCorrectionLogged(context, t, explicitLow = it, glucoseAtTime = v) }

            val plateauBefore = shadowNm.getNotification(AlertNotifier.PLATEAU_ALERT_NOTIFICATION_ID)
            val corrBefore = shadowNm.getNotification(AlertNotifier.CORRECTION_ALERT_NOTIFICATION_ID)
            PlateauCoordinator.evaluate(context, points.toList())
            val plateauAfter = shadowNm.getNotification(AlertNotifier.PLATEAU_ALERT_NOTIFICATION_ID)
            val corrAfter = shadowNm.getNotification(AlertNotifier.CORRECTION_ALERT_NOTIFICATION_ID)

            val state = toDisplayState(context, raw, null, null)
            AlertCoordinator.evaluate(context, state, null)
            val red = shadowNm.getNotification(AlertNotifier.RED_ALERT_NOTIFICATION_ID)
            val yellow = shadowNm.getNotification(AlertNotifier.YELLOW_ALERT_NOTIFICATION_ID)

            val r = state as? GlucoseDisplayState.Reading
            val step = Step(
                index = i, sgv = v,
                severity = r?.severity ?: if (state is GlucoseDisplayState.Stale) "STALE" else "none",
                projected = r?.projected,
                redTitle = title(red),
                redPostedAudibly = postedAudibly(prevRed, red),
                yellowPostedAudibly = postedAudibly(prevYellow, yellow),
                anyAudible = postedAudibly(prevRed, red) || postedAudibly(prevYellow, yellow) ||
                    postedAudibly(plateauBefore, plateauAfter) || postedAudibly(corrBefore, corrAfter),
            )
            steps += step
            val events = (if (i in corrections) "{CORRECTION ${if (corrections[i] == true) "low" else "high"}} " else "") +
                slotEvent("PLATEAU", plateauBefore, plateauAfter) + slotEvent("CORR", corrBefore, corrAfter) +
                slotEvent("RED", prevRed, red) + slotEvent("YEL", prevYellow, yellow)
            out.append(
                String.format(
                    Locale.US, "%4d  %3d  %5s  %3s  %3s  %-6s  %s\n", i * 5, v,
                    raw.ratePerMinute?.let { String.format(Locale.US, "%+.1f", it) } ?: "n/a",
                    r?.projected?.toString() ?: "-", r?.projectedExtended?.toString() ?: "-", step.severity, events.trim(),
                ),
            )
            prevRed = red
            prevYellow = yellow
        }
        File("build/scenario-replay").apply { mkdirs() }.resolve("$name.txt").writeText(out.toString())
        return steps
    }

    // --------------------------------------------------------------------------------------------
    // The owner's real 2026-09-22 trace
    // --------------------------------------------------------------------------------------------

    private val real20260922 = listOf(
        123, 127, 132, 134, 139, 147, 153, 163, 179, 191, 200, 208, 216, 217, 212, 202, 189, 170, 149, 128, 109, 93, 81, 75,
        79, 87, 89, 83, 74, 68, 69, 78, 90, 95, 96, 93, 88, 81, 76, 74, 81, 92, 103,
    )
    private val secondLowIndex = 28 // 22:27, 74 mg/dL, falling -1.8
    private val thirdLowIndex = 39  // 23:22, 74 mg/dL

    @Test
    fun `real 2026-09-22 - the second low at 74 is RED, not yellow`() {
        val steps = replay("REAL_2026-09-22", real20260922)
        val s = steps[secondLowIndex]
        assertEquals("74 falling -1.8 (projected ~47) must score red", "red", s.severity)
        assertNotNull("a red alert must be showing at the second low", s.redTitle)
    }

    @Test
    fun `real 2026-09-22 - every reading at or under 74 has a red alert showing`() {
        val steps = replay("REAL_2026-09-22_b", real20260922)
        for (s in steps.filter { it.sgv <= 74 && it.index >= secondLowIndex }) {
            assertNotNull("no red alert showing at ${s.sgv} (index ${s.index})", s.redTitle)
        }
    }

    @Test
    fun `real 2026-09-22 - the third low at 74 is announced audibly, not silently`() {
        val steps = replay("REAL_2026-09-22_c", real20260922)
        val window = steps.subList(thirdLowIndex - 3, thirdLowIndex + 1)
        assertTrue("the 96 -> 74 re-drop got no audible alert: $window", window.any { it.anyAudible })
    }

    // --------------------------------------------------------------------------------------------
    // Urgency wording
    // --------------------------------------------------------------------------------------------

    @Test
    fun `a real 57 is always URGENT even when the fall has eased`() {
        val steps = replay(
            "overshoot_into_57",
            listOf(262, 270, 279, 288, 296, 300, 304, 306, 305, 300, 292, 282, 270, 257, 244, 230, 216, 202, 188, 174, 160, 146, 132, 119, 107, 96, 86, 77, 69, 62, 57),
            corrections = mapOf(5 to false),
        )
        val last = steps.last()
        assertTrue("57 mg/dL must read URGENT, was: ${last.redTitle}", last.redTitle?.contains("URGENT") == true)
    }

    @Test
    fun `a gentle drift into the low band does not scream URGENT on its first alert`() {
        val steps = replay(
            "gentle_drift",
            listOf(142, 136, 131, 127, 124, 122, 121, 118, 114, 111, 109, 108, 106, 102, 98, 95, 93, 92, 90, 86, 82, 79, 77, 76, 75, 74, 72),
        )
        val firstRed = steps.first { it.redTitle != null }
        assertFalse("first red for a -0.6 drift said: ${firstRed.redTitle}", firstRed.redTitle!!.contains("URGENT"))
    }

    @Test
    fun `a steep drop with nothing on board still alerts before crossing 70`() {
        val steps = replay("steep_drop_no_cause", listOf(118, 114, 110, 106, 104, 100, 96, 90, 83, 75, 67, 60, 55))
        val firstAlert = steps.first { it.anyAudible }
        assertTrue("first audible alert came too late, at ${firstAlert.sgv}", firstAlert.sgv > 70)
    }

    // --------------------------------------------------------------------------------------------
    // Ticket 014 - explicit correction direction
    // --------------------------------------------------------------------------------------------

    @Test
    fun `a correction from 300 that lands well produces no audible alert after the correction`() {
        val steps = replay(
            "correction_lands_150",
            listOf(262, 270, 279, 288, 296, 300, 304, 306, 305, 300, 292, 282, 270, 257, 244, 231, 219, 208, 198, 189, 181, 174, 168, 163, 159, 156, 154),
            corrections = mapOf(5 to false),
        )
        val loud = steps.drop(6).filter { it.anyAudible }
        assertTrue("audible alerts during an expected correction drop: $loud", loud.isEmpty())
    }

    private fun setLatest(value: Int, time: Long) = LatestTrendRepository.updateRawReading(
        context, RawReading(value = value, time = time, ratePerMinute = null, deltaFromPrevious = null),
    )

    @Test
    fun `juice at 74 and insulin at 230 register when the person says which`() {
        setLatest(74, base)
        PlateauCoordinator.onCorrectionLogged(context, base, explicitLow = true, glucoseAtTime = 74)
        assertEquals(base, PlateauCoordinator.activeLowCorrectionAnchorMs(context))

        val later = base + 60 * 60_000L
        SystemClock.setCurrentTimeMillis(later)
        setLatest(230, later)
        PlateauCoordinator.onCorrectionLogged(context, later, explicitLow = false, glucoseAtTime = 230)
        assertEquals(later, PlateauCoordinator.activeHighCorrectionAnchorMs(context))
    }

    @Test
    fun `a backdated inferred correction is judged by the glucose at that time, not now`() {
        SystemClock.setCurrentTimeMillis(base + 25 * 60_000L)
        setLatest(95, base + 25 * 60_000L)
        PlateauCoordinator.onCorrectionLogged(context, base, glucoseAtTime = 62)
        assertEquals(base, PlateauCoordinator.activeLowCorrectionAnchorMs(context))
    }

    @Test
    fun `late-logged juice during a rebound is never recorded as a high correction`() {
        SystemClock.setCurrentTimeMillis(base + 50 * 60_000L)
        setLatest(262, base + 50 * 60_000L)
        PlateauCoordinator.onCorrectionLogged(context, base, explicitLow = true, glucoseAtTime = 58)
        assertNull(PlateauCoordinator.activeHighCorrectionAnchorMs(context))
    }
}
