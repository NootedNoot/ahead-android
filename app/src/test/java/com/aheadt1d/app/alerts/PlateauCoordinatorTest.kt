package com.aheadt1d.app.alerts

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.state.RawReading
import com.aheadt1d.app.tuning.PlateauTuningParameters
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Covers PlateauCoordinator's correction-response tracking - in particular a
 * direct regression test for the INCONCLUSIVE fix (CorrectionResponseMath):
 * a missing/null reading at window-close must NOT silently clear the
 * tracking window the way a genuine resolution does.
 *
 * "Window elapsed" scenarios are simulated by logging the correction with an
 * explicit past [timestamp] (onCorrectionLogged's own parameter for exactly
 * this - a backdated chart-point log), rather than fast-forwarding a global
 * clock: Robolectric's SystemClock shadow doesn't reliably propagate to
 * plain System.currentTimeMillis() reads across versions/looper modes, so
 * this is the robust way to control elapsed time here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlateauCoordinatorTest {

    private lateinit var context: Context
    private lateinit var shadowNm: org.robolectric.shadows.ShadowNotificationManager

    private val plateauPrefsName = "ahead_plateau_state"

    private val tuning = PlateauTuningParameters() // defaults: lowThreshold=70, lowCorrectionWindowMinutes=20

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowNm = shadowOf(nm)
        context.getSharedPreferences(plateauPrefsName, Context.MODE_PRIVATE).edit().clear().commit()
        LatestTrendRepository.updateRawReading(
            context,
            RawReading(value = 60, time = System.currentTimeMillis(), ratePerMinute = null, deltaFromPrevious = null),
        )
    }

    private fun point(minutesAgo: Long, sgv: Int): GlucosePoint {
        val now = System.currentTimeMillis()
        return GlucosePoint(Instant.ofEpochMilli(now - minutesAgo * 60_000L), sgv)
    }

    private fun correctionNotification() = shadowNm.getNotification(AlertNotifier.CORRECTION_ALERT_NOTIFICATION_ID)

    @Test
    fun `logging a correction while low opens a low-direction tracking window`() {
        // Backdated 25 minutes - past the 20-minute low window - so the very
        // next evaluate() call sees an already-elapsed window.
        PlateauCoordinator.onCorrectionLogged(context, timestamp = System.currentTimeMillis() - 25 * 60_000L, tuning = tuning)

        PlateauCoordinator.evaluate(context, listOf(point(1, 60), point(0, 60)), tuning)

        assertNotNull("expected a correction-not-responding alert", correctionNotification())
    }

    @Test
    fun `still inside the window posts nothing yet`() {
        // Only 5 of the 20-minute low window has elapsed.
        PlateauCoordinator.onCorrectionLogged(context, timestamp = System.currentTimeMillis() - 5 * 60_000L, tuning = tuning)

        PlateauCoordinator.evaluate(context, listOf(point(1, 60), point(0, 60)), tuning)

        assertNull("window still open - nothing should fire yet", correctionNotification())
    }

    @Test
    fun `genuine resolution clears tracking so a later empty read does not falsely re-fire`() {
        PlateauCoordinator.onCorrectionLogged(context, timestamp = System.currentTimeMillis() - 25 * 60_000L, tuning = tuning)

        // Window elapsed, glucose recovered well above lowThreshold (70).
        PlateauCoordinator.evaluate(context, listOf(point(1, 80), point(0, 85)), tuning)
        assertNull("resolved - must not fire", correctionNotification())

        // A later cycle with no usable points (simulating a transient HC gap)
        // must be a no-op, not a stale re-evaluation of a cleared window -
        // this only holds if RESPONDING_OR_RESOLVED actually cleared
        // KEY_CORRECTION_LOGGED_AT above.
        PlateauCoordinator.evaluate(context, emptyList(), tuning)
        assertNull(correctionNotification())
    }

    @Test
    fun `a missing reading at window-close does not silently drop the tracking window`() {
        // Regression test for the CorrectionResponseMath null-handling bug:
        // previously, currentValue == null at window-close was read as
        // RESPONDING_OR_RESOLVED (same as a real resolution), so
        // PlateauCoordinator cleared the tracking window and the "not
        // responding" check for this episode was gone for good, even though
        // glucose was never actually confirmed to have recovered.
        PlateauCoordinator.onCorrectionLogged(context, timestamp = System.currentTimeMillis() - 25 * 60_000L, tuning = tuning)

        // Window already elapsed, but this cycle has no usable points at all
        // (empty list -> currentValue null) - a stand-in for a transient
        // Health Connect read gap landing on exactly the wrong cycle.
        PlateauCoordinator.evaluate(context, emptyList(), tuning)
        assertNull("inconclusive cycle must not fire on missing data", correctionNotification())

        // Next cycle, real data comes back and glucose is still low and flat -
        // the window must still be open (not silently cleared by the
        // inconclusive cycle above), so this now correctly fires.
        PlateauCoordinator.evaluate(context, listOf(point(1, 60), point(0, 60)), tuning)
        assertNotNull(
            "tracking should have survived the inconclusive cycle and still fire on real not-responding data",
            correctionNotification(),
        )
    }
}
