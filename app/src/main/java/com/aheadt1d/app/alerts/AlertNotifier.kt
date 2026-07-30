package com.aheadt1d.app.alerts

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aheadt1d.app.MainActivity
import com.aheadt1d.app.R
import com.aheadt1d.app.emergency.EmergencyAlertScheduler
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import com.aheadt1d.app.notifications.NotificationIconFactory
import com.aheadt1d.app.state.ReadBlockedReason
import com.aheadt1d.app.state.staleGuidance
import com.aheadt1d.app.voice.VoiceAlertCategory
import com.aheadt1d.app.voice.VoiceAlertEngine

/**
 * Builds and posts the yellow/red ALERT notifications - the interrupting
 * ones, distinct from the silent ongoing status notification (id 1001 in
 * GlucoseStatusService). AlertCoordinator owns *when* these fire; this
 * object only owns what they look like.
 */
object AlertNotifier {
    const val RED_ALERT_NOTIFICATION_ID = 2001
    const val YELLOW_ALERT_NOTIFICATION_ID = 2002
    // Own slots, deliberately separate from red/yellow: the plateau and
    // correction-response checks are independent signals (PlateauCoordinator)
    // that can be active at the same time as a rate-based alert, not a
    // replacement for one - see the class doc on PlateauCoordinator.
    const val PLATEAU_ALERT_NOTIFICATION_ID = 2003
    const val CORRECTION_ALERT_NOTIFICATION_ID = 2004

    private const val REQ_RED_FSI = 2101
    private const val REQ_RED_CONTENT = 2102
    private const val REQ_YELLOW_CONTENT = 2103
    private const val REQ_SIGNAL_LOST_CONTENT = 2104
    private const val REQ_PLATEAU_CONTENT = 2105
    private const val REQ_CORRECTION_CONTENT = 2106
    private const val REQ_SIGNAL_LOST_FSI = 2107

    /**
     * @param recovering True only for a low-side red that's rising as
     *   expected (see AlertCoordinator's low-recovery handling) - the person
     *   is already being warned and is trending back to safety, so this
     *   fires a calmer heads-up instead of the full emergency treatment: no
     *   full-screen takeover, no CATEGORY_ALARM sound, "recovering" copy
     *   instead of "URGENT check now". A genuinely falling low, or ANY high
     *   (highs never get the calmer treatment - see AlertCoordinator's peak
     *   tracking), still gets the full takeover.
     */
    fun showRedAlert(context: Context, value: Int, projected: Int?, rate: Double?, recovering: Boolean = false) {
        AlertChannels.ensure(context)
        val arrow = GlucoseTrendArrow.fromRatePerMinute(rate)

        val builder = Notification.Builder(context, AlertChannels.currentRedChannelId(context))
            .setSmallIcon(NotificationIconFactory.readingIcon(context, value, arrow))
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.low))
            .setContentIntent(mainActivityIntent(context, REQ_RED_CONTENT))

        if (recovering) {
            builder
                .setContentTitle("🟠 Still low: $value mg/dL, rising")
                .setContentText("${projectionLine(projected)} — keep monitoring")
                .setCategory(Notification.CATEGORY_STATUS)
        } else {
            // FLAG_UPDATE_CURRENT is load-bearing: successive red intents are
            // filterEquals-identical (extras don't participate), so without it a
            // cached PendingIntent would launch the takeover screen with the
            // FIRST alert's values forever.
            val fullScreenIntent = PendingIntent.getActivity(
                context,
                REQ_RED_FSI,
                RedAlertActivity.createIntent(context, value, projected, rate),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder
                .setContentTitle("🔴 URGENT: $value mg/dL ${arrow.label}")
                .setContentText("${projectionLine(projected)} — check now")
                .setCategory(Notification.CATEGORY_ALARM)
                // Full content on the lock screen: for a genuine red alert,
                // hiding the number behind "notification hidden" would
                // defeat the point.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenIntent, true)
        }

        notifyIfAllowed(context) { nm ->
            nm.notify(RED_ALERT_NOTIFICATION_ID, builder.build())
            nm.cancel(YELLOW_ALERT_NOTIFICATION_ID)
        }

        // Voice is independent of the visual notification (and its permission):
        // the engine gates itself on the voice settings and does nothing more.
        val spokenText = if (recovering) {
            "Still low at $value, but rising. ${spokenProjection(projected)} Keep monitoring."
        } else {
            "Urgent. Glucose $value ${spokenDirection(rate)}. ${spokenProjection(projected)} Check now."
        }
        VoiceAlertEngine.speak(context, VoiceAlertCategory.RED, spokenText)
    }

    /** Yellow never escalates: no full-screen intent, no DND bypass (its
     *  channel never sets it), default lock-screen privacy. Sound comes from
     *  the channel. */
    fun showYellowAlert(context: Context, value: Int, projected: Int?, rate: Double?) {
        AlertChannels.ensure(context)
        val arrow = GlucoseTrendArrow.fromRatePerMinute(rate)

        val notification = Notification.Builder(context, AlertChannels.YELLOW_CHANNEL_ID)
            .setSmallIcon(NotificationIconFactory.readingIcon(context, value, arrow))
            .setContentTitle("⚠️ $value mg/dL ${arrow.label}")
            .setContentText("${projectionLine(projected)} — keep an eye on it")
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.high))
            .setContentIntent(mainActivityIntent(context, REQ_YELLOW_CONTENT))
            .build()

        notifyIfAllowed(context) { nm -> nm.notify(YELLOW_ALERT_NOTIFICATION_ID, notification) }

        VoiceAlertEngine.speak(
            context,
            VoiceAlertCategory.YELLOW,
            "Heads up. Glucose $value ${spokenDirection(rate)}. ${spokenProjection(projected)}"
        )
    }

    /**
     * Fired once glucose data has gone stale (see AlertCoordinator.handleStale) -
     * unconditionally, regardless of what the last CONFIRMED severity was.
     * RED-tier, same delivery as [showRedAlert]: the red/DND-bypassing channel,
     * full-screen takeover, alarm-stream sound. 2026-07-27: previously
     * yellow-tier and only fired if the last known reading was already
     * concerning ("you were heading somewhere bad and we've lost signal,
     * rather than escalate on a guess"). Reclassified because a total data
     * blackout is dangerous on its own merits - the person could be dropping
     * or climbing fast starting the MOMENT signal was lost, with zero
     * indication, regardless of what the last confirmed value happened to be.
     * Never claims a glucose number or severity, since none is confirmed -
     * see RedAlertActivity.createSignalLostIntent's distinct "no data" screen.
     *
     * [blockedReason] is the runner's app-side diagnosis when one exists -
     * the guidance sentence (shared staleGuidance) then points at the app-side
     * fix instead of sending the user to their sensor. Defaults to null so
     * debug force-fire callers keep the generic copy.
     */
    fun showSignalLostAlert(
        context: Context,
        lastValue: Int,
        lastArrow: GlucoseTrendArrow,
        ageMinutes: Long,
        blockedReason: ReadBlockedReason? = null,
    ) {
        AlertChannels.ensure(context)

        // FLAG_UPDATE_CURRENT for the same reason showRedAlert's does: successive
        // signal-lost intents are filterEquals-identical, so without it a cached
        // PendingIntent would relaunch the takeover with the FIRST episode's values.
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            REQ_SIGNAL_LOST_FSI,
            RedAlertActivity.createSignalLostIntent(context, lastValue, lastArrow, ageMinutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, AlertChannels.currentRedChannelId(context))
            .setSmallIcon(NotificationIconFactory.warningIcon(context))
            .setContentTitle("🔴 No new glucose data — ${ageMinutes}m")
            .setContentText("Last reading $lastValue mg/dL ${lastArrow.label}, ${ageMinutes}m ago. ${staleGuidance(blockedReason)}")
            .setCategory(Notification.CATEGORY_ALARM)
            // Same reasoning as showRedAlert: hiding the last-known number
            // behind "notification hidden" would defeat the point of a
            // takeover-tier alert.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.low))
            .setContentIntent(mainActivityIntent(context, REQ_SIGNAL_LOST_CONTENT))
            .setFullScreenIntent(fullScreenIntent, true)
            .build()

        // Shares RED_ALERT_NOTIFICATION_ID with showRedAlert - deliberately: a
        // live glucose-red notification left over from before the blackout
        // started is now unconfirmed information anyway, so the more accurate
        // "we don't actually know your current state" message should replace
        // it rather than stack alongside it (same one-urgent-slot-at-a-time
        // precedent as yellow/signal-lost sharing YELLOW_ALERT_NOTIFICATION_ID
        // before this change).
        notifyIfAllowed(context) { nm -> nm.notify(RED_ALERT_NOTIFICATION_ID, notification) }

        val spokenAdvice = when (blockedReason) {
            ReadBlockedReason.PERMISSION_MISSING -> "Ahead lost its Health Connect permission. Open the app to fix it."
            ReadBlockedReason.HC_UNAVAILABLE -> "Health Connect is unavailable. Open the Ahead app."
            null -> "Check your sensor or connection now."
        }
        VoiceAlertEngine.speak(
            context,
            VoiceAlertCategory.SIGNAL_LOST,
            "Urgent. No new glucose data for $ageMinutes minutes. Last reading was $lastValue. $spokenAdvice"
        )
    }

    /**
     * Gap 1 (sustained-high-plateau): observational, never diagnostic -
     * "this has been elevated for X, hasn't started trending down," never a
     * dosing suggestion. Always the yellow channel/color regardless of tier -
     * escalation shows up in the wording ("longer than before"), not in
     * delivery mechanism, since a flat-but-high value isn't the acute
     * crash-risk a fast negative rate is. [tier] 1 is the first fire at
     * highDurationMinutes; 2+ is every escalation step beyond that.
     */
    fun showPlateauAlert(
        context: Context,
        value: Int,
        durationMinutes: Long,
        tier: Int,
        highThreshold: Int,
        highDurationMinutes: Int,
    ) {
        AlertChannels.ensure(context)

        val (title, text) = if (tier <= 1) {
            "⚠️ Elevated for ${highDurationMinutes}+ min" to
                "$value mg/dL — has been at or above $highThreshold mg/dL for over $highDurationMinutes minutes and hasn't started trending down."
        } else {
            "⚠️ Still elevated" to
                "$value mg/dL — now over $durationMinutes minutes at or above $highThreshold mg/dL, longer than before. Still hasn't started trending down."
        }

        val notification = Notification.Builder(context, AlertChannels.YELLOW_CHANNEL_ID)
            .setSmallIcon(NotificationIconFactory.readingIcon(context, value, GlucoseTrendArrow.FLAT))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.high))
            .setContentIntent(mainActivityIntent(context, REQ_PLATEAU_CONTENT))
            .build()

        notifyIfAllowed(context) { nm -> nm.notify(PLATEAU_ALERT_NOTIFICATION_ID, notification) }

        VoiceAlertEngine.speak(context, VoiceAlertCategory.PLATEAU, text)
    }

    fun cancelPlateau(context: Context) {
        NotificationManagerCompat.from(context).cancel(PLATEAU_ALERT_NOTIFICATION_ID)
    }

    /**
     * Gap 2 (correction not responding): fires once a correction-response
     * window has elapsed with glucose still on the wrong side of threshold
     * and no meaningful rate in the expected direction - >= HIGH_THRESHOLD
     * with no downward rate for a high-side correction (insulin), or <=
     * LOW_THRESHOLD with no upward rate for a low-side one (fast carbs, see
     * [isLow]). Neutral, non-diagnostic wording - flags it, doesn't explain
     * it or suggest a dose. [plateauActive] ties the message to an
     * already-flagged plateau when one is active (high-side only - there's
     * no low-side plateau concept), per the spec's "escalatory relative to
     * Gap 1, not a replacement" framing.
     */
    fun showCorrectionNotRespondingAlert(
        context: Context,
        value: Int,
        minutesSinceCorrection: Long,
        plateauActive: Boolean,
        isLow: Boolean = false,
    ) {
        AlertChannels.ensure(context)

        val verb = if (isLow) "rising" else "trending down"
        val text = if (plateauActive) {
            "Still $value mg/dL and hasn't started $verb since the correction was logged — part of the same elevated stretch flagged earlier."
        } else {
            "Still $value mg/dL and hasn't started $verb since the correction was logged."
        }
        // Same low/high value-direction color convention showRedAlert/
        // showYellowAlert already use - not a severity tier, just which side
        // of range this is about.
        val colorRes = if (isLow) R.color.low else R.color.high

        val notification = Notification.Builder(context, AlertChannels.YELLOW_CHANNEL_ID)
            .setSmallIcon(NotificationIconFactory.readingIcon(context, value, GlucoseTrendArrow.FLAT))
            .setContentTitle("⚠️ Correction logged ${minutesSinceCorrection}m ago")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, colorRes))
            .setContentIntent(mainActivityIntent(context, REQ_CORRECTION_CONTENT))
            .build()

        notifyIfAllowed(context) { nm -> nm.notify(CORRECTION_ALERT_NOTIFICATION_ID, notification) }

        VoiceAlertEngine.speak(context, VoiceAlertCategory.CORRECTION, text)
    }

    /**
     * Gap 2 (repeat correction): purely informational/awareness that a
     * second correction was logged close together while still elevated - no
     * dosing guidance, no judgment language, same reasoning as
     * bolus-stacking-awareness. Shares [CORRECTION_ALERT_NOTIFICATION_ID]
     * with the not-responding message - most-recent-state-wins in one slot,
     * same pattern as the red/yellow/plateau alerts.
     */
    fun showRepeatCorrectionAlert(context: Context, minutesSinceFirstCorrection: Long, isLow: Boolean = false) {
        AlertChannels.ensure(context)

        val direction = if (isLow) "low" else "elevated"
        val text = "A second correction was logged $minutesSinceFirstCorrection minutes after the first, while glucose was still $direction."
        val colorRes = if (isLow) R.color.low else R.color.high

        val notification = Notification.Builder(context, AlertChannels.YELLOW_CHANNEL_ID)
            .setSmallIcon(NotificationIconFactory.warningIcon(context))
            .setContentTitle("📝 Another correction logged")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, colorRes))
            .setContentIntent(mainActivityIntent(context, REQ_CORRECTION_CONTENT))
            .build()

        notifyIfAllowed(context) { nm -> nm.notify(CORRECTION_ALERT_NOTIFICATION_ID, notification) }

        VoiceAlertEngine.speak(context, VoiceAlertCategory.CORRECTION, text)
    }

    fun cancelCorrection(context: Context) {
        NotificationManagerCompat.from(context).cancel(CORRECTION_ALERT_NOTIFICATION_ID)
    }

    /** Every call site that clears the red slot is, by construction, the app
     *  itself determining a red episode is no longer active (dismissed by the
     *  user, downgraded, or auto-resolved after a signal-loss reconnect) - so
     *  this is also the one place a pending emergency-contact auto-text timer
     *  (see EmergencyAlertScheduler) should be torn down. Safe/no-op when
     *  nothing is pending. */
    fun cancelRed(context: Context) {
        NotificationManagerCompat.from(context).cancel(RED_ALERT_NOTIFICATION_ID)
        EmergencyAlertScheduler.cancel(context)
    }

    /** Just the shared yellow/signal-lost slot - deliberately narrower than
     *  cancelAlerts() so clearing a stale signal-lost notification can never
     *  also clobber a currently-live red alert sitting in the other slot. */
    fun cancelYellow(context: Context) {
        NotificationManagerCompat.from(context).cancel(YELLOW_ALERT_NOTIFICATION_ID)
    }

    fun cancelAlerts(context: Context) {
        cancelRed(context)
        NotificationManagerCompat.from(context).cancel(YELLOW_ALERT_NOTIFICATION_ID)
    }

    private fun projectionLine(projected: Int?): String =
        if (projected != null) "Projected $projected mg/dL in 15 min" else "Glucose trending out of range"

    /** Spoken-word direction (no "↓" glyphs), with an urgency cue for steep moves. */
    private fun spokenDirection(rate: Double?): String = when {
        rate == null -> "trend unknown"
        rate <= -2.0 -> "and falling fast"
        rate < 0 -> "and falling"
        rate >= 2.0 -> "and rising fast"
        rate > 0 -> "and rising"
        else -> "and holding steady"
    }

    private fun spokenProjection(projected: Int?): String =
        if (projected != null) "Projected $projected in fifteen minutes." else ""

    private fun mainActivityIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

    private inline fun notifyIfAllowed(context: Context, block: (NotificationManagerCompat) -> Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            block(NotificationManagerCompat.from(context))
        }
    }
}
