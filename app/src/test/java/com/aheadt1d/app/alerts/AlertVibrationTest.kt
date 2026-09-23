package com.aheadt1d.app.alerts

import android.app.Application
import android.content.Context
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVibrator

/**
 * GAP FOUND 2026-09-22, live on the owner's phone: a custom threshold set at 68 (falling) fired
 * a correctly-posted, correctly-configured-on-paper notification while the phone's ringer was in
 * Silent - no sound, no vibration felt. Traced with `adb shell dumpsys audio`: this phone's
 * ringer-silent state mutes STREAM_NOTIFICATION directly (independent of Do Not Disturb, and
 * independent of whether the channel's setBypassDnd actually stuck - see AlertChannels' own doc
 * for why that flag can silently fail to apply without Notification Policy Access ever granted,
 * confirmed missing on-device: `enabled_notification_policy_access_packages` was null).
 * STREAM_ALARM was NOT in that device's ringer-muted-streams list.
 *
 * Fix: AlertTones.vibrate() is a direct Vibrator call using alarm-usage attributes, completely
 * independent of any NotificationChannel - so it does not depend on Notification Policy Access,
 * on a channel's bypassDnd flag having actually stuck, or on which audio stream the phone's
 * ringer state happens to mute. These tests exercise it directly and through the three real
 * call sites (red, signal-lost, custom-threshold), the same way AlertCoordinatorTest exercises
 * AlertCoordinator's real entry points rather than reaching into internals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertVibrationTest {

    private lateinit var context: Context
    private lateinit var shadowVibrator: ShadowVibrator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        shadowVibrator = shadowOf(vibrator)
        for (p in listOf("ahead_alert_state", "ahead_alert_channels", "ahead_plateau_state", "ahead_alert_silence", "ahead_custom_thresholds")) {
            context.getSharedPreferences(p, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    // =================================================================================
    // AlertTones.vibrate() itself
    // =================================================================================

    @Test
    fun `vibrate runs the vibrator with the given pattern when nothing is silenced`() {
        AlertTones.vibrate(context, AlertChannels.RED_VIBRATION_PATTERN)
        assertTrue("expected the vibrator to have been used", shadowVibrator.isVibrating)
    }

    @Test
    fun `vibrate does nothing while alerts are silenced and ignoreSilence is false`() {
        AlertSilenceManager.silence(context, 15)
        AlertTones.vibrate(context, AlertChannels.RED_VIBRATION_PATTERN)
        assertFalse("silenced alerts must not vibrate by default", shadowVibrator.isVibrating)
    }

    @Test
    fun `vibrate still runs through ordinary silence when ignoreSilence is true`() {
        AlertSilenceManager.silence(context, 15)
        AlertTones.vibrate(context, AlertChannels.CUSTOM_THRESHOLD_VIBRATION_PATTERN, ignoreSilence = true)
        assertTrue(
            "a custom threshold is supposed to override Ahead's own silence toggle, not just Android's DND",
            shadowVibrator.isVibrating,
        )
    }

    @Test
    fun `vibrate is blocked by the dev kill switch even with ignoreSilence true`() {
        AlertSilenceManager.setDevKillSwitch(context, true)
        AlertTones.vibrate(context, AlertChannels.RED_VIBRATION_PATTERN, ignoreSilence = true)
        assertFalse("the dev kill switch outranks even an override-silence alert", shadowVibrator.isVibrating)
    }

    // =================================================================================
    // The real call sites - red, signal-lost, custom-threshold
    // =================================================================================

    @Test
    fun `a fresh red alert vibrates directly, not just through the channel`() {
        AlertNotifier.showRedAlert(context, value = 55, projected = 50, rate = -2.0)
        assertTrue(shadowVibrator.isVibrating)
    }

    @Test
    fun `a silent red re-post does not re-vibrate`() {
        // Silent re-posts restore the visible tray indicator for an alert the person already
        // heard and dismissed - re-buzzing would be exactly the nag this path exists to avoid
        // (see AlertCoordinator's low clear-hysteresis re-post and AlertNotifier's own doc).
        AlertNotifier.showRedAlert(context, value = 62, projected = 58, rate = -1.0, silent = true)
        assertFalse("a silent re-post must not vibrate", shadowVibrator.isVibrating)
    }

    @Test
    fun `signal-lost vibrates directly`() {
        AlertNotifier.showSignalLostAlert(context, lastValue = 90, lastArrow = com.aheadt1d.app.notifications.GlucoseTrendArrow.FLAT, ageMinutes = 20)
        assertTrue(shadowVibrator.isVibrating)
    }

    @Test
    fun `a muted-while-silenced signal-lost alert does not vibrate`() {
        AlertSilenceManager.silence(context, 15)
        AlertNotifier.showSignalLostAlert(
            context, lastValue = 90, lastArrow = com.aheadt1d.app.notifications.GlucoseTrendArrow.FLAT,
            ageMinutes = 20, allowWhileSilenced = true,
        )
        assertFalse(
            "muted-while-silenced means the notification shows but stays quiet - noise defeats the point",
            shadowVibrator.isVibrating,
        )
    }

    @Test
    fun `a posted custom threshold alert vibrates even while Ahead's own silence is on`() {
        AlertSilenceManager.silence(context, 15)
        val threshold = CustomThreshold(
            id = "t1", kind = CustomThreshold.Kind.VALUE, direction = CustomThreshold.Direction.FALLING,
            amount = 68.0, label = "", temporary = false,
        )
        val posted = AlertNotifier.showCustomThresholdAlert(context, threshold, currentValue = 66, currentRate = -0.5, metric = 66.0)
        assertTrue("setup: expected the notification to actually post", posted)
        assertTrue(
            "a custom threshold is documented to override Ahead's own silence toggle - vibration must too",
            shadowVibrator.isVibrating,
        )
    }

    @Test
    fun `a custom threshold alert is still blocked by the dev kill switch`() {
        AlertSilenceManager.setDevKillSwitch(context, true)
        val threshold = CustomThreshold(
            id = "t1", kind = CustomThreshold.Kind.VALUE, direction = CustomThreshold.Direction.FALLING,
            amount = 68.0, label = "", temporary = false,
        )
        val posted = AlertNotifier.showCustomThresholdAlert(context, threshold, currentValue = 66, currentRate = -0.5, metric = 66.0)
        assertFalse("setup: the dev kill switch should block the post itself", posted)
        assertFalse(shadowVibrator.isVibrating)
    }

    // =================================================================================
    // "Vibrations based off of alert type and urgency" - 2026-09-22, the owner's own follow-up.
    // Each pair below is the SAME alert tier/notification, differing only in the one axis that's
    // supposed to change the felt pattern - so a passing test proves the pattern actually moved,
    // not just that vibration happened at all.
    // =================================================================================

    private fun lastPattern(): LongArray? = shadowVibrator.pattern

    @Test
    fun `a recovering red vibrates a different pattern than an urgent red`() {
        AlertNotifier.showRedAlert(context, value = 65, projected = 68, rate = 1.0, recovering = true)
        val recoveringPattern = lastPattern()
        AlertNotifier.showRedAlert(context, value = 55, projected = 50, rate = -2.0, recovering = false)
        val urgentPattern = lastPattern()
        assertTrue("setup: expected to capture both patterns", recoveringPattern != null && urgentPattern != null)
        assertTrue(
            "recovering (${recoveringPattern?.toList()}) and urgent (${urgentPattern?.toList()}) " +
                "must feel different - urgency, not just tier, should be felt",
            !recoveringPattern.contentEquals(urgentPattern),
        )
        assertArrayEquals(AlertChannels.RED_RECOVERING_VIBRATION_PATTERN, recoveringPattern)
        assertArrayEquals(AlertChannels.RED_URGENT_VIBRATION_PATTERN, urgentPattern)
    }

    @Test
    fun `signal lost while dropping vibrates red's urgent pattern, not the uncertain one`() {
        AlertNotifier.showSignalLostAlert(
            context, lastValue = 80, lastArrow = com.aheadt1d.app.notifications.GlucoseTrendArrow.DOUBLE_DOWN, ageMinutes = 5,
        )
        assertArrayEquals(AlertChannels.SIGNAL_LOST_DROPPING_VIBRATION_PATTERN, lastPattern())
    }

    @Test
    fun `ordinary signal lost vibrates the uncertain pattern, distinct from a confirmed drop`() {
        AlertNotifier.showSignalLostAlert(
            context, lastValue = 100, lastArrow = com.aheadt1d.app.notifications.GlucoseTrendArrow.FLAT, ageMinutes = 20,
        )
        val uncertain = lastPattern()
        assertArrayEquals(AlertChannels.SIGNAL_LOST_UNCERTAIN_VIBRATION_PATTERN, uncertain)
        assertTrue(
            "an ordinary blackout must not feel identical to a confirmed dangerous drop",
            !uncertain.contentEquals(AlertChannels.SIGNAL_LOST_DROPPING_VIBRATION_PATTERN),
        )
    }

    @Test
    fun `yellow vibrates a different pattern low-side vs high-side`() {
        AlertNotifier.showYellowAlert(context, value = 75, projected = 72, rate = -0.5)
        val lowPattern = lastPattern()
        AlertNotifier.showYellowAlert(context, value = 190, projected = 205, rate = 1.0)
        val highPattern = lastPattern()
        assertArrayEquals(AlertChannels.YELLOW_LOW_VIBRATION_PATTERN, lowPattern)
        assertArrayEquals(AlertChannels.YELLOW_HIGH_VIBRATION_PATTERN, highPattern)
        assertTrue("low-side and high-side yellow must feel different", !lowPattern.contentEquals(highPattern))
    }

    @Test
    fun `yellow vibration respects Ahead's own silence toggle, matching its tone`() {
        AlertSilenceManager.silence(context, 15)
        AlertNotifier.showYellowAlert(context, value = 75, projected = 72, rate = -0.5)
        assertFalse("yellow is not supposed to override Ahead's own silence toggle", shadowVibrator.isVibrating)
    }

    @Test
    fun `yellow vibration is skipped on a silent tray-only update`() {
        AlertNotifier.showYellowAlert(context, value = 75, projected = 72, rate = -0.5, silent = true)
        assertFalse(shadowVibrator.isVibrating)
    }

    @Test
    fun `custom threshold vibrates a different pattern falling vs rising`() {
        val falling = CustomThreshold(
            id = "f", kind = CustomThreshold.Kind.VALUE, direction = CustomThreshold.Direction.FALLING,
            amount = 68.0, label = "", temporary = false,
        )
        AlertNotifier.showCustomThresholdAlert(context, falling, currentValue = 66, currentRate = -0.5, metric = 66.0)
        val fallingPattern = lastPattern()

        val rising = CustomThreshold(
            id = "r", kind = CustomThreshold.Kind.VALUE, direction = CustomThreshold.Direction.RISING,
            amount = 250.0, label = "", temporary = false,
        )
        AlertNotifier.showCustomThresholdAlert(context, rising, currentValue = 255, currentRate = 3.0, metric = 255.0)
        val risingPattern = lastPattern()

        assertArrayEquals(AlertChannels.CUSTOM_THRESHOLD_FALLING_VIBRATION_PATTERN, fallingPattern)
        assertArrayEquals(AlertChannels.CUSTOM_THRESHOLD_RISING_VIBRATION_PATTERN, risingPattern)
        assertTrue("falling and rising custom thresholds must feel different", !fallingPattern.contentEquals(risingPattern))
    }

    // =================================================================================
    // Channel configuration - the sound attributes that made this bug possible
    // =================================================================================

    @Test
    fun `the custom threshold channel sound uses the alarm stream, not the notification stream`() {
        AlertChannels.ensure(context)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AlertChannels.currentCustomChannelId(context))
        val usage = channel?.audioAttributes?.usage
        assertTrue(
            "custom-threshold's channel sound must route to STREAM_ALARM (not STREAM_NOTIFICATION, " +
                "which this exact bug showed can be muted by ordinary ringer-silent, independent of DND) - " +
                "got usage=$usage",
            usage == android.media.AudioAttributes.USAGE_ALARM,
        )
    }

    @Test
    fun `an existing custom threshold channel from before the fix gets migrated to the new sound`() {
        // Simulates a real existing install: a channel created under the OLD scheme (version 6,
        // USAGE_NOTIFICATION) sitting in SharedPreferences/NotificationManager already, exactly
        // like the owner's own phone had. ensure() must detect and migrate it, not just apply the
        // new behavior to brand-new installs.
        context.getSharedPreferences("ahead_alert_channels", Context.MODE_PRIVATE).edit()
            .putInt("sound_scheme_version", 6)
            .commit()
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val oldChannel = android.app.NotificationChannel(
            "glucose_alerts_custom_threshold", "Custom glucose thresholds", android.app.NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build(),
            )
        }
        nm.createNotificationChannel(oldChannel)

        AlertChannels.ensure(context)

        val migratedId = AlertChannels.currentCustomChannelId(context)
        assertTrue("expected the channel id to change (a new version)", migratedId != "glucose_alerts_custom_threshold")
        val migratedChannel = nm.getNotificationChannel(migratedId)
        assertTrue(
            "the migrated channel must use the alarm stream",
            migratedChannel?.audioAttributes?.usage == android.media.AudioAttributes.USAGE_ALARM,
        )
    }
}
