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

    private const val REQ_RED_CONTENT = 2102
    private const val REQ_YELLOW_CONTENT = 2103
    private const val REQ_SIGNAL_LOST_CONTENT = 2104
    private const val REQ_PLATEAU_CONTENT = 2105
    private const val REQ_CORRECTION_CONTENT = 2106

    // Same 70 mg/dL split AlertCoordinator keeps its own copy of - decides
    // which direction's tone plays. Also considers
    // projected like AlertCoordinator's own copy does (see its doc): a
    // still-high current value that's projected to crash into the low band
    // should get the falling/low tone, not the rising/high one.
    // isLowSide/LOW_HIGH_SPLIT moved to AlertThresholds.kt (2026-08-26) -
    // shared with AlertCoordinator, see that file's doc for why.

    /**
     * REMOVED 2026-08-20: the full-screen takeover (RedAlertActivity) is
     * gone, at the owner's explicit request - reported as more headache
     * (an alarm they couldn't disable) than help. This is now an ordinary
     * notification for both branches, same delivery tier as yellow, just
     * with red's own color/copy/channel.
     *
     * @param recovering True only for a low-side red that's rising as
     *   expected (see AlertCoordinator's low-recovery handling) - the person
     *   is already being warned and is trending back to safety, so this
     *   fires calmer copy ("recovering") instead of "URGENT check now".
     * @param projectedExtended the 30-min projection, for AlertExplainer's
     *   one-liner - see that class's own doc for when it picks this over
     *   the 15-min [projected] window. Optional/nullable so existing debug
     *   or test call sites that don't have it keep compiling unchanged.
     */
    fun showRedAlert(context: Context, value: Int, projected: Int?, rate: Double?, recovering: Boolean = false, projectedExtended: Int? = null) {
        if (AlertSilenceManager.isSilenced(context)) return
        AlertChannels.ensure(context)
        val arrow = GlucoseTrendArrow.fromRatePerMinute(rate)

        // Alert Transparency: the plain-language "why" (AlertExplainer),
        // read-only against the same numbers already driving this alert -
        // see that class's own doc. The detail line backs the notification's
        // native expand-to-see-more affordance (BigTextStyle) rather than a
        // dedicated screen, since the full-screen takeover this alert used
        // to have (RedAlertActivity) was removed 2026-08-20 at the owner's
        // own request - this is the real "alert screen" now.
        val explanation = AlertExplainer.oneLiner(value, rate, projected, projectedExtended)
        val detail = AlertExplainer.detailLine(value, rate, projected, projectedExtended)

        val builder = Notification.Builder(context, AlertChannels.currentRedChannelId(context))
            .setSmallIcon(NotificationIconFactory.readingIcon(context, value, arrow))
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.low))
            .setContentIntent(mainActivityIntent(context, REQ_RED_CONTENT))
            .setCategory(Notification.CATEGORY_STATUS)
            // Full content on the lock screen: for a genuine red alert,
            // hiding the number behind "notification hidden" would
            // defeat the point.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.BigTextStyle().bigText(detail))

        if (recovering) {
            builder
                .setContentTitle("🟠 Still low: $value mg/dL, rising")
                .setContentText("$explanation — keep monitoring")
        } else {
            builder
                .setContentTitle("🔴 URGENT: $value mg/dL ${arrow.label}")
                .setContentText("$explanation — check now")
        }
        builder.addAction(snoozeAction(context, 15))

        notifyIfAllowed(context) { nm ->
            nm.notify(RED_ALERT_NOTIFICATION_ID, builder.build())
            nm.cancel(YELLOW_ALERT_NOTIFICATION_ID)
        }

        // 2026-08-01: red-tier alerts play NO tone at all, in either branch.
        // First cut only silenced the takeover (non-recovering) path and left
        // `recovering`'s WARN_LOW tone in place - but a sticky, slowly-
        // resolving low spends most of its time in exactly that recovering
        // state, wobbling above/below zero rate, so that leftover tone was
        // still firing constantly and was reported as "the little alarm that
        // fires on a screen takeover" even though this branch never took the
        // screen over. Red is voice + vibration + notification only, full
        // stop - the spoken value says what to DO, the vibration reaches
        // someone without waking a room. Voice is ungated (see
        // VoiceAlertEngine.UNGATED_CATEGORIES) so it's never the silent link.

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
     *  channel never sets it), default lock-screen privacy. Tone is a
     *  single directional sweep (deliberately calmer than red's three
     *  chirps) and, like the channel itself, respects DND rather than
     *  piercing it.
     *
     *  @param projectedExtended see [showRedAlert]'s matching doc. */
    fun showYellowAlert(context: Context, value: Int, projected: Int?, rate: Double?, projectedExtended: Int? = null) {
        if (AlertSilenceManager.isSilenced(context)) return
        AlertChannels.ensure(context)
        val arrow = GlucoseTrendArrow.fromRatePerMinute(rate)

        val explanation = AlertExplainer.oneLiner(value, rate, projected, projectedExtended)
        val detail = AlertExplainer.detailLine(value, rate, projected, projectedExtended)

        val notification = Notification.Builder(context, AlertChannels.currentYellowChannelId(context))
            .setSmallIcon(NotificationIconFactory.readingIcon(context, value, arrow))
            .setContentTitle("⚠️ $value mg/dL ${arrow.label}")
            .setContentText("$explanation — keep an eye on it")
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.high))
            .setContentIntent(mainActivityIntent(context, REQ_YELLOW_CONTENT))
            .addAction(snoozeAction(context, 15))
            .build()

        notifyIfAllowed(context) { nm -> nm.notify(YELLOW_ALERT_NOTIFICATION_ID, notification) }

        AlertTones.play(context, if (isLowSide(value, projected)) AlertTones.Tone.WARN_LOW else AlertTones.Tone.WARN_HIGH)

        VoiceAlertEngine.speak(
            context,
            VoiceAlertCategory.YELLOW,
            "Heads up. Glucose $value ${spokenDirection(rate)}. ${spokenProjection(projected)}"
        )
    }

    /**
     * Fired once glucose data has gone stale (see AlertCoordinator.handleStale) -
     * unconditionally, regardless of what the last CONFIRMED severity was.
     * RED-tier, same delivery as [showRedAlert]: the red/DND-bypassing channel.
     * 2026-07-27: previously yellow-tier and only fired if the last known
     * reading was already concerning ("you were heading somewhere bad and
     * we've lost signal, rather than escalate on a guess"). Reclassified
     * because a total data blackout is dangerous on its own merits - the
     * person could be dropping or climbing fast starting the MOMENT signal
     * was lost, with zero indication, regardless of what the last confirmed
     * value happened to be. Never claims a glucose number or severity, since
     * none is confirmed.
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
        if (AlertSilenceManager.isSilenced(context)) return
        AlertChannels.ensure(context)

        val notification = Notification.Builder(context, AlertChannels.currentRedChannelId(context))
            .setSmallIcon(NotificationIconFactory.warningIcon(context))
            .setContentTitle("🔴 No new glucose data — ${ageMinutes}m")
            .setContentText("Last reading $lastValue mg/dL ${lastArrow.label}, ${ageMinutes}m ago. ${staleGuidance(blockedReason)}")
            .setCategory(Notification.CATEGORY_STATUS)
            // Full content on the lock screen: hiding the last-known number
            // behind "notification hidden" would defeat the point.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.low))
            .setContentIntent(mainActivityIntent(context, REQ_SIGNAL_LOST_CONTENT))
            .addAction(snoozeAction(context, 15))
            .build()

        // Shares RED_ALERT_NOTIFICATION_ID with showRedAlert - deliberately: a
        // live glucose-red notification left over from before the blackout
        // started is now unconfirmed information anyway, so the more accurate
        // "we don't actually know your current state" message should replace
        // it rather than stack alongside it (same one-urgent-slot-at-a-time
        // precedent as yellow/signal-lost sharing YELLOW_ALERT_NOTIFICATION_ID
        // before this change).
        notifyIfAllowed(context) { nm -> nm.notify(RED_ALERT_NOTIFICATION_ID, notification) }

        AlertTones.play(context, AlertTones.Tone.SIGNAL_LOST)

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
        if (AlertSilenceManager.isSilenced(context)) return
        AlertChannels.ensure(context)

        val (title, text) = if (tier <= 1) {
            "⚠️ Elevated for ${highDurationMinutes}+ min" to
                "$value mg/dL — has been at or above $highThreshold mg/dL for over $highDurationMinutes minutes and hasn't started trending down."
        } else {
            "⚠️ Still elevated" to
                "$value mg/dL — now over $durationMinutes minutes at or above $highThreshold mg/dL, longer than before. Still hasn't started trending down."
        }

        val notification = Notification.Builder(context, AlertChannels.currentYellowChannelId(context))
            .setSmallIcon(NotificationIconFactory.readingIcon(context, value, GlucoseTrendArrow.FLAT))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.high))
            .setContentIntent(mainActivityIntent(context, REQ_PLATEAU_CONTENT))
            .build()

        notifyIfAllowed(context) { nm -> nm.notify(PLATEAU_ALERT_NOTIFICATION_ID, notification) }

        AlertTones.play(context, AlertTones.Tone.CALM_HIGH)

        // Spoken separately from the visual `text` above - that copy reads
        // dense/awkward aloud ("has been at or above X mg/dL for over Y
        // minutes"); this is the same information in a shorter, more natural
        // spoken cadence.
        val spokenText = if (tier <= 1) {
            "Heads up. Glucose has been elevated for over $highDurationMinutes minutes and hasn't started coming down."
        } else {
            "Heads up. Glucose is still elevated, now over $durationMinutes minutes — longer than before."
        }
        VoiceAlertEngine.speak(context, VoiceAlertCategory.PLATEAU, spokenText)
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
        if (AlertSilenceManager.isSilenced(context)) return
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

        val notification = Notification.Builder(context, AlertChannels.currentYellowChannelId(context))
            .setSmallIcon(NotificationIconFactory.readingIcon(context, value, GlucoseTrendArrow.FLAT))
            .setContentTitle("⚠️ Correction logged ${minutesSinceCorrection}m ago")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, colorRes))
            .setContentIntent(mainActivityIntent(context, REQ_CORRECTION_CONTENT))
            .build()

        notifyIfAllowed(context) { nm -> nm.notify(CORRECTION_ALERT_NOTIFICATION_ID, notification) }

        AlertTones.play(context, if (isLow) AlertTones.Tone.CALM_LOW else AlertTones.Tone.CALM_HIGH)

        // Spoken separately from the visual `text` above for the same
        // reason showPlateauAlert's is - shorter, more natural aloud.
        val spokenText = "Heads up. It's been $minutesSinceCorrection minutes since your correction, and glucose hasn't started $verb yet — still $value."
        VoiceAlertEngine.speak(context, VoiceAlertCategory.CORRECTION, spokenText)
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
        if (AlertSilenceManager.isSilenced(context)) return
        AlertChannels.ensure(context)

        val direction = if (isLow) "low" else "elevated"
        val text = "A second correction was logged $minutesSinceFirstCorrection minutes after the first, while glucose was still $direction."
        val colorRes = if (isLow) R.color.low else R.color.high

        val notification = Notification.Builder(context, AlertChannels.currentYellowChannelId(context))
            .setSmallIcon(NotificationIconFactory.warningIcon(context))
            .setContentTitle("📝 Another correction logged")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, colorRes))
            .setContentIntent(mainActivityIntent(context, REQ_CORRECTION_CONTENT))
            .build()

        notifyIfAllowed(context) { nm -> nm.notify(CORRECTION_ALERT_NOTIFICATION_ID, notification) }

        AlertTones.play(context, if (isLow) AlertTones.Tone.CALM_LOW else AlertTones.Tone.CALM_HIGH)

        val spokenText = "Just a note — another correction was logged $minutesSinceFirstCorrection minutes after the first, glucose still $direction."
        VoiceAlertEngine.speak(context, VoiceAlertCategory.CORRECTION, spokenText)
    }

    fun cancelCorrection(context: Context) {
        NotificationManagerCompat.from(context).cancel(CORRECTION_ALERT_NOTIFICATION_ID)
    }

    fun cancelRed(context: Context) {
        NotificationManagerCompat.from(context).cancel(RED_ALERT_NOTIFICATION_ID)
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

    private fun snoozeAction(context: Context, minutes: Int = 15): Notification.Action {
        val intent = AlertSnoozeReceiver.createIntent(context, minutes)
        val pending = PendingIntent.getBroadcast(
            context,
            minutes,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(
            null,
            "Snooze ${minutes}m",
            pending
        ).build()
    }

    private inline fun notifyIfAllowed(context: Context, block: (NotificationManagerCompat) -> Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            block(NotificationManagerCompat.from(context))
        }
    }
}
