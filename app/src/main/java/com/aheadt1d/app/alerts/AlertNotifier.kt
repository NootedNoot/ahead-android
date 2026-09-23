package com.aheadt1d.app.alerts

import android.Manifest
import android.annotation.SuppressLint
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
import com.aheadt1d.app.state.DebugGlucoseOverride
import com.aheadt1d.app.state.ReadBlockedReason
import com.aheadt1d.app.state.staleGuidance
import com.aheadt1d.app.voice.VoiceAlertCategory
import com.aheadt1d.app.voice.VoiceAlertEngine
import java.util.Locale

/**
 * Builds and posts the yellow/red ALERT notifications - the interrupting
 * ones, distinct from the silent ongoing status notification (id 1001 in
 * GlucoseStatusService). AlertCoordinator owns *when* these fire; this
 * object only owns what they look like.
 */
@SuppressLint("MissingPermission")
object AlertNotifier {
    const val RED_ALERT_NOTIFICATION_ID = 2001
    const val YELLOW_ALERT_NOTIFICATION_ID = 2002
    // Own slots, deliberately separate from red/yellow: the plateau and
    // correction-response checks are independent signals (PlateauCoordinator)
    // that can be active at the same time as a rate-based alert, not a
    // replacement for one - see the class doc on PlateauCoordinator.
    const val PLATEAU_ALERT_NOTIFICATION_ID = 2003
    const val CORRECTION_ALERT_NOTIFICATION_ID = 2004
    // Custom thresholds get a RANGE, not one fixed id: unlike the tiers
    // above (one active state at a time each), Ryan can have several
    // independent thresholds crossed simultaneously (a value one AND a rate
    // one), and each should keep its own notification rather than clobber
    // the others. Derived deterministically from the threshold's own id so
    // the SAME threshold's repeat "escalated" fire replaces its own prior
    // notification instead of stacking duplicates - see
    // customThresholdNotificationId below.
    private const val CUSTOM_THRESHOLD_ID_BASE = 2500
    private const val CUSTOM_THRESHOLD_ID_RANGE = 100000

    private const val REQ_RED_CONTENT = 2102
    private const val REQ_YELLOW_CONTENT = 2103
    private const val REQ_SIGNAL_LOST_CONTENT = 2104
    private const val REQ_PLATEAU_CONTENT = 2105
    private const val REQ_CORRECTION_CONTENT = 2106
    private const val REQ_CUSTOM_THRESHOLD_CONTENT = 2107

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
     * @param lowPhase Low-side urgency/state phase (2026-09-23 ticket - see LowAlertPhase's own
     *   doc for what each value means and how AlertCoordinator decides it). Defaults to URGENT,
     *   which is also what every high-side call site leaves it at - the high side has no
     *   low/not-low or stability concept, just its own flat cooldown, so URGENT's existing
     *   "check now" copy is the correct unconditional wording there, unchanged from before this
     *   parameter existed (when it was a plain `recovering: Boolean = false`).
     * @param projectedExtended the 30-min projection, for AlertExplainer's
     *   one-liner - see that class's own doc for when it picks this over
     *   the 15-min [projected] window. Optional/nullable so existing debug
     *   or test call sites that don't have it keep compiling unchanged.
     */
    fun showRedAlert(
        context: Context,
        value: Int,
        projected: Int?,
        rate: Double?,
        lowPhase: LowAlertPhase = LowAlertPhase.URGENT,
        projectedExtended: Int? = null,
        isInjected: Boolean = DebugGlucoseOverride.isActive,
        // 2026-09-20: a RE-POST of an alert the person already heard and then dismissed, while
        // they are still held inside the low band (see AlertCoordinator's clear-hysteresis).
        // Restores the visible indicator without re-interrupting - re-sounding something they
        // deliberately swiped away is exactly the undismissable-alarm behaviour that got the
        // full-screen takeover removed on 2026-08-20.
        silent: Boolean = false,
    ) {
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
        val fullDetail = if (isInjected) "${DebugGlucoseOverride.DISCLAIMER}\n$detail" else detail

        val prefix = if (isInjected) DebugGlucoseOverride.TITLE_PREFIX else ""
        val bodyPrefix = if (isInjected) DebugGlucoseOverride.BODY_PREFIX else ""

        // A silent re-post uses the quiet channel: from API 26 the channel owns sound and
        // vibration, so this is the only way to restore the visible alert without re-buzzing.
        val builder = Notification.Builder(
            context,
            if (silent) AlertChannels.QUIET_CHANNEL_ID else AlertChannels.currentRedChannelId(context),
        )
            .setGroup(AlertChannels.NOTIFICATION_GROUP_KEY)
            .setSmallIcon(NotificationIconFactory.readingIcon(context, value, arrow))
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.low))
            .setContentIntent(mainActivityIntent(context, REQ_RED_CONTENT))
            .setCategory(Notification.CATEGORY_STATUS)
            // Full content on the lock screen: for a genuine red alert,
            // hiding the number behind "notification hidden" would
            // defeat the point.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.BigTextStyle().bigText(fullDetail))

        if (isInjected) {
            builder.setSubText(DebugGlucoseOverride.DISCLAIMER_SHORT)
        }

        // Copy varies by phase, not just a recovering/urgent binary (2026-09-23 ticket) - see
        // LowAlertPhase's own doc. Real bug this replaces: a 79 mg/dL reading (already above the
        // app's own 70 mg/dL threshold) still said "Still low... rising," because the old binary
        // only ever distinguished "rate positive" from "rate not positive," with no separate
        // concept for "this isn't low anymore, it just isn't CONFIRMED stable yet."
        when (lowPhase) {
            LowAlertPhase.URGENT -> builder
                .setContentTitle("${prefix}🔴 URGENT: $value mg/dL ${arrow.label}")
                .setContentText("$bodyPrefix$explanation — check now")
            LowAlertPhase.STANDARD -> builder
                .setContentTitle(
                    if (value < LOW_HIGH_SPLIT) "${prefix}🟠 Low: $value mg/dL ${arrow.label}"
                    else "${prefix}🟠 Heading low: $value mg/dL ${arrow.label}",
                )
                .setContentText("$bodyPrefix$explanation — treat and monitor")
            LowAlertPhase.RISING -> builder
                .setContentTitle("${prefix}🟠 Low but rising: $value mg/dL")
                .setContentText("$bodyPrefix$explanation — informational, no need to re-treat yet")
            LowAlertPhase.RECOVERING -> builder
                .setContentTitle("${prefix}🟡 Recovering — not yet stable: $value mg/dL")
                .setContentText("$bodyPrefix$explanation — back above 70, confirming it holds")
        }
        builder.addAction(snoozeAction(context, 15))

        notifyIfAllowed(context) { nm ->
            nm.notify(RED_ALERT_NOTIFICATION_ID, builder.build())
            nm.cancel(YELLOW_ALERT_NOTIFICATION_ID)
        }

        // A silent re-post restores the visible indicator only - the person already heard this
        // alert and dismissed it; speaking it again would be the nag this deliberately avoids.
        if (silent) return

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

        // Direct, channel-independent vibration guarantee (2026-09-22) - see
        // AlertTones.vibrate's own doc for the real incident this closes. Placed after the
        // `if (silent) return` above so a silent re-post (already-acknowledged, tray-only
        // restore) still doesn't re-buzz - same rule voice already follows.
        // Pattern varies with [lowPhase] the same way the copy above already does - urgency, not
        // just tier, is felt (see AlertChannels' patterns for the design language). URGENT and
        // STANDARD both get the sharper pattern (still a real, active low, under 70 mg/dL, that
        // needs a felt alert whether or not it's actively worsening); RISING and RECOVERING both
        // get the calmer one (already turning around or already back over threshold).
        AlertTones.vibrate(
            context,
            when (lowPhase) {
                LowAlertPhase.URGENT, LowAlertPhase.STANDARD -> AlertChannels.RED_URGENT_VIBRATION_PATTERN
                LowAlertPhase.RISING, LowAlertPhase.RECOVERING -> AlertChannels.RED_RECOVERING_VIBRATION_PATTERN
            },
        )

        // Voice is independent of the visual notification (and its permission):
        // the engine gates itself on the voice settings and does nothing more.
        val spokenText = SpokenAlertText.red(value, rate, projected, projectedExtended, lowPhase)
        VoiceAlertEngine.speak(context, VoiceAlertCategory.RED, spokenText)
    }

    /** Yellow never escalates: no full-screen intent, no DND bypass (its
     *  channel never sets it), default lock-screen privacy. Tone is a
     *  single directional sweep (deliberately calmer than red's three
     *  chirps) and, like the channel itself, respects DND rather than
     *  piercing it.
     *
     *  @param projectedExtended see [showRedAlert]'s matching doc. */
    fun showYellowAlert(
        context: Context,
        value: Int,
        projected: Int?,
        rate: Double?,
        projectedExtended: Int? = null,
        isInjected: Boolean = DebugGlucoseOverride.isActive,
        silent: Boolean = false,
    ) {
        if (AlertSilenceManager.isSilenced(context)) return
        AlertChannels.ensure(context)
        val arrow = GlucoseTrendArrow.fromRatePerMinute(rate)

        val explanation = AlertExplainer.oneLiner(value, rate, projected, projectedExtended)
        val detail = AlertExplainer.detailLine(value, rate, projected, projectedExtended)
        val fullDetail = if (isInjected) "${DebugGlucoseOverride.DISCLAIMER}\n$detail" else detail

        val prefix = if (isInjected) DebugGlucoseOverride.TITLE_PREFIX else ""
        val bodyPrefix = if (isInjected) DebugGlucoseOverride.BODY_PREFIX else ""

        val channelId = if (silent) AlertChannels.QUIET_CHANNEL_ID else AlertChannels.currentYellowChannelId(context)
        val builder = Notification.Builder(context, channelId)
            .setGroup(AlertChannels.NOTIFICATION_GROUP_KEY)
            .setSmallIcon(NotificationIconFactory.readingIcon(context, value, arrow))
            .setContentTitle("${prefix}⚠️ $value mg/dL ${arrow.label}")
            .setContentText("$bodyPrefix$explanation — keep an eye on it")
            .setStyle(Notification.BigTextStyle().bigText(fullDetail))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.high))
            .setContentIntent(mainActivityIntent(context, REQ_YELLOW_CONTENT))
            .addAction(snoozeAction(context, 15))

        if (isInjected) {
            builder.setSubText(DebugGlucoseOverride.DISCLAIMER_SHORT)
        }

        val notification = builder.build()

        notifyIfAllowed(context) { nm -> nm.notify(YELLOW_ALERT_NOTIFICATION_ID, notification) }

        // A silent update refreshes the visible tray indicator without sounds or speech
        if (silent) return

        // Direct vibration (2026-09-22), added alongside the tone/pattern differentiation below -
        // yellow never had ANY direct vibration before this, only whatever the channel itself
        // carried. Deliberately default ignoreSilence (NOT passed here, so it stays false) -
        // unlike red/custom-threshold, yellow is still meant to respect Ahead's own silence
        // toggle, exactly like its tone call right below already does. Pattern varies by
        // direction, the same split the tone call uses.
        val isLow = isLowSideYellow(value, projected)
        AlertTones.vibrate(context, if (isLow) AlertChannels.YELLOW_LOW_VIBRATION_PATTERN else AlertChannels.YELLOW_HIGH_VIBRATION_PATTERN)

        AlertTones.play(context, if (isLow) AlertTones.Tone.WARN_LOW else AlertTones.Tone.WARN_HIGH)

        VoiceAlertEngine.speak(
            context,
            VoiceAlertCategory.YELLOW,
            SpokenAlertText.yellow(value, rate, projected, projectedExtended)
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
    /**
     * [allowWhileSilenced] (2026-09-20): a data blackout is not a glucose alert. Silencing says
     * "stop telling me about my glucose"; it cannot sensibly also mean "and stop telling me you
     * have gone blind" - especially since silenceIndefinitely() never expires and is reachable
     * from the main screen. When this is true the notification is still POSTED while silenced,
     * but muted: no tone, no voice, and setSilent so the red channel's own vibration pattern
     * doesn't fire either. The person keeps an honest, visible "no data" indicator without the
     * silence they asked for being overridden by noise.
     */
    fun showSignalLostAlert(
        context: Context,
        lastValue: Int,
        lastArrow: GlucoseTrendArrow,
        ageMinutes: Long,
        blockedReason: ReadBlockedReason? = null,
        isInjected: Boolean = DebugGlucoseOverride.isActive,
        allowWhileSilenced: Boolean = false,
    ) {
        val silenced = AlertSilenceManager.isSilenced(context)
        if (silenced && !allowWhileSilenced) return
        AlertChannels.ensure(context)

        val isDropping = lastArrow == GlucoseTrendArrow.SLOWLY_FALLING ||
                         lastArrow == GlucoseTrendArrow.DOWN ||
                         lastArrow == GlucoseTrendArrow.DOUBLE_DOWN

        val prefix = if (isInjected) DebugGlucoseOverride.TITLE_PREFIX else ""
        val bodyPrefix = if (isInjected) DebugGlucoseOverride.BODY_PREFIX else ""
        val baseText = if (isDropping) {
            "Last reading was $lastValue mg/dL ${lastArrow.label}, ${ageMinutes}m ago. Check blood sugar immediately!"
        } else {
            "Last reading $lastValue mg/dL ${lastArrow.label}, ${ageMinutes}m ago. ${staleGuidance(blockedReason)}"
        }
        val fullDetail = if (isInjected) "${DebugGlucoseOverride.DISCLAIMER}\n$baseText" else baseText

        val title = if (isDropping) {
            "${prefix}🔴 CRITICAL: Signal lost while dropping — ${ageMinutes}m"
        } else {
            "${prefix}🔴 No new glucose data — ${ageMinutes}m"
        }

        // Visible but mute while silenced - the channel owns sound/vibration from API 26, so
        // this is the only way to post without interrupting. See AlertChannels.QUIET_CHANNEL_ID.
        val channelId = if (silenced) AlertChannels.QUIET_CHANNEL_ID else AlertChannels.currentRedChannelId(context)
        val builder = Notification.Builder(context, channelId)
            .setGroup(AlertChannels.NOTIFICATION_GROUP_KEY)
            .setSmallIcon(NotificationIconFactory.warningIcon(context))
            .setContentTitle(title)
            .setContentText("$bodyPrefix$baseText")
            .setStyle(Notification.BigTextStyle().bigText(fullDetail))
            .setCategory(Notification.CATEGORY_STATUS)
            // Full content on the lock screen: hiding the last-known number
            // behind "notification hidden" would defeat the point.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.low))
            .setContentIntent(mainActivityIntent(context, REQ_SIGNAL_LOST_CONTENT))
            .addAction(snoozeAction(context, 15))

        if (isInjected) {
            builder.setSubText(DebugGlucoseOverride.DISCLAIMER_SHORT)
        }
        if (silenced) {
            builder.setSubText("Alerts are silenced — showing this anyway")
        }

        val notification = builder.build()

        // Shares RED_ALERT_NOTIFICATION_ID with showRedAlert - deliberately: a
        // live glucose-red notification left over from before the blackout
        // started is now unconfirmed information anyway, so the more accurate
        // "we don't actually know your current state" message should replace
        // it rather than stack alongside it (same one-urgent-slot-at-a-time
        // precedent as yellow/signal-lost sharing YELLOW_ALERT_NOTIFICATION_ID
        // before this change).
        notifyIfAllowed(context) { nm -> nm.notify(RED_ALERT_NOTIFICATION_ID, notification) }

        // Muted while silenced: the notification above is the whole point, the noise is not.
        if (silenced) return

        // Direct, channel-independent vibration guarantee (2026-09-22) - see AlertTones.vibrate's
        // own doc. Pattern varies with [isDropping] the same way the title/text/voice above
        // already do: a confirmed drop-while-blind is as dangerous as a real red and gets red's
        // own pattern; an ordinary blackout gets a distinct "uncertain" one, matching
        // AlertTones.Tone.SIGNAL_LOST's own "alternating wobble" identity.
        AlertTones.vibrate(
            context,
            if (isDropping) AlertChannels.SIGNAL_LOST_DROPPING_VIBRATION_PATTERN else AlertChannels.SIGNAL_LOST_UNCERTAIN_VIBRATION_PATTERN,
        )

        AlertTones.play(context, AlertTones.Tone.SIGNAL_LOST)

        val spokenAdvice = when (blockedReason) {
            ReadBlockedReason.PERMISSION_MISSING -> "Ahead lost its Health Connect permission. Open the app to fix it."
            ReadBlockedReason.HC_UNAVAILABLE -> "Health Connect is unavailable. Open the Ahead app."
            null -> "Check your sensor or connection now."
        }
        if (isDropping) {
            VoiceAlertEngine.speak(
                context,
                VoiceAlertCategory.RED,
                "Urgent emergency. Glucose signal lost while falling. Last reading was $lastValue. Check your blood sugar immediately."
            )
        } else {
            VoiceAlertEngine.speak(
                context,
                VoiceAlertCategory.SIGNAL_LOST,
                "Urgent. No new glucose data for $ageMinutes minutes. Last reading was $lastValue. $spokenAdvice"
            )
        }
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
        isInjected: Boolean = DebugGlucoseOverride.isActive,
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

        val prefix = if (isInjected) DebugGlucoseOverride.TITLE_PREFIX else ""
        val bodyPrefix = if (isInjected) DebugGlucoseOverride.BODY_PREFIX else ""
        val fullDetail = if (isInjected) "${DebugGlucoseOverride.DISCLAIMER}\n$text" else text

        val builder = Notification.Builder(context, AlertChannels.currentYellowChannelId(context))
            .setGroup(AlertChannels.NOTIFICATION_GROUP_KEY)
            .setSmallIcon(NotificationIconFactory.readingIcon(context, value, GlucoseTrendArrow.FLAT))
            .setContentTitle("$prefix$title")
            .setContentText("$bodyPrefix$text")
            .setStyle(Notification.BigTextStyle().bigText(fullDetail))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.high))
            .setContentIntent(mainActivityIntent(context, REQ_PLATEAU_CONTENT))
            .addAction(snoozeAction(context, 15))

        if (isInjected) {
            builder.setSubText(DebugGlucoseOverride.DISCLAIMER_SHORT)
        }

        val notification = builder.build()

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
        isInjected: Boolean = DebugGlucoseOverride.isActive,
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

        val prefix = if (isInjected) DebugGlucoseOverride.TITLE_PREFIX else ""
        val bodyPrefix = if (isInjected) DebugGlucoseOverride.BODY_PREFIX else ""
        val fullDetail = if (isInjected) "${DebugGlucoseOverride.DISCLAIMER}\n$text" else text

        val builder = Notification.Builder(context, AlertChannels.currentYellowChannelId(context))
            .setGroup(AlertChannels.NOTIFICATION_GROUP_KEY)
            .setSmallIcon(NotificationIconFactory.readingIcon(context, value, GlucoseTrendArrow.FLAT))
            .setContentTitle("${prefix}⚠️ Correction logged ${minutesSinceCorrection}m ago")
            .setContentText("$bodyPrefix$text")
            .setStyle(Notification.BigTextStyle().bigText(fullDetail))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, colorRes))
            .setContentIntent(mainActivityIntent(context, REQ_CORRECTION_CONTENT))
            .addAction(snoozeAction(context, 15))

        if (isInjected) {
            builder.setSubText(DebugGlucoseOverride.DISCLAIMER_SHORT)
        }

        val notification = builder.build()

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
    fun showRepeatCorrectionAlert(
        context: Context,
        minutesSinceFirstCorrection: Long,
        isLow: Boolean = false,
        isInjected: Boolean = DebugGlucoseOverride.isActive,
    ) {
        if (AlertSilenceManager.isSilenced(context)) return
        AlertChannels.ensure(context)

        val direction = if (isLow) "low" else "elevated"
        val text = "A second correction was logged $minutesSinceFirstCorrection minutes after the first, while glucose was still $direction."
        val colorRes = if (isLow) R.color.low else R.color.high

        val prefix = if (isInjected) DebugGlucoseOverride.TITLE_PREFIX else ""
        val bodyPrefix = if (isInjected) DebugGlucoseOverride.BODY_PREFIX else ""
        val fullDetail = if (isInjected) "${DebugGlucoseOverride.DISCLAIMER}\n$text" else text

        val builder = Notification.Builder(context, AlertChannels.currentYellowChannelId(context))
            .setGroup(AlertChannels.NOTIFICATION_GROUP_KEY)
            .setSmallIcon(NotificationIconFactory.warningIcon(context))
            .setContentTitle("${prefix}📝 Another correction logged")
            .setContentText("$bodyPrefix$text")
            .setStyle(Notification.BigTextStyle().bigText(fullDetail))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, colorRes))
            .setContentIntent(mainActivityIntent(context, REQ_CORRECTION_CONTENT))

        if (isInjected) {
            builder.setSubText(DebugGlucoseOverride.DISCLAIMER_SHORT)
        }

        val notification = builder.build()

        notifyIfAllowed(context) { nm -> nm.notify(CORRECTION_ALERT_NOTIFICATION_ID, notification) }

        AlertTones.play(context, if (isLow) AlertTones.Tone.CALM_LOW else AlertTones.Tone.CALM_HIGH)

        val spokenText = "Just a note — another correction was logged $minutesSinceFirstCorrection minutes after the first, glucose still $direction."
        VoiceAlertEngine.speak(context, VoiceAlertCategory.CORRECTION, spokenText)
    }

    fun cancelCorrection(context: Context) {
        NotificationManagerCompat.from(context).cancel(CORRECTION_ALERT_NOTIFICATION_ID)
    }

    /**
     * The one deliberate override-silence tier in this file: a user-defined
     * [CustomThreshold] the owner explicitly set up to punch through both
     * Android's Do Not Disturb (the channel bypasses it, same mechanism red
     * already uses safely) AND Ahead's own in-app silence killswitch - see
     * the MISSING `AlertSilenceManager.isSilenced(context)` check every other
     * function in this file starts with. That's not an oversight: the whole
     * point of this feature (per the owner's own spec) is "this specific
     * limit is important enough to punch through silent mode when and only
     * when it's crossed" - not a general "ignore silence" bit. What keeps
     * this from becoming the same "stuck loud" failure the 2026-08-20
     * siren removal was about: CustomThresholdCoordinator only ever calls
     * this on a FRESH_CROSS or ESCALATED outcome (see CustomThresholdMath) -
     * never on "still crossed, nothing new," never repeating on its own, and
     * never louder than one ordinary notification-volume sound + vibration
     * pattern - no forced alarm stream, no lock-screen takeover, dismissible
     * exactly like any other notification.
     *
     * Deliberately no voice-alert call here (unlike the other tiers above) -
     * VoiceAlertEngine.speak() itself checks isSilenced() internally, so
     * wiring voice in here today would either silently no-op during silence
     * (defeating the point) or need its own separate bypass path. Left out
     * of this first pass rather than half-wiring it; the channel's own sound
     * + distinct vibration pattern already satisfies "force an audible
     * alert."
     *
     * No snooze action (unlike red/yellow/plateau/correction above) -
     * deliberately removed 2026-09-13: AlertSnoozeReceiver's snooze action
     * unconditionally calls AlertSilenceManager.silence(), the ordinary
     * silence layer this exact notification tier exists specifically to
     * bypass. Tapping "Snooze" here would have quietly wired this
     * override-silence alert back into the very mechanism it's designed to
     * ignore - confusing at best. This notification already auto-cancels on
     * tap/dismiss and won't repeat until a genuinely new crossing/escalation,
     * so no snooze affordance is actually needed.
     *
     * Returns whether the notification was actually posted (false when
     * POST_NOTIFICATIONS isn't granted, or the dev kill switch blocked it) -
     * CustomThresholdCoordinator only persists "fired" state when this
     * returns true, so a permission gap can't silently mark a threshold as
     * having alerted when nobody was actually told anything.
     */
    fun showCustomThresholdAlert(
        context: Context,
        threshold: CustomThreshold,
        currentValue: Int,
        currentRate: Double?,
        metric: Double?,
        isInjected: Boolean = DebugGlucoseOverride.isActive,
    ): Boolean {
        // The ONE check this function needs despite skipping isSilenced() -
        // the dev kill switch outranks even a custom threshold's own
        // override power. See AlertSilenceManager's class doc.
        if (AlertSilenceManager.isDevKillSwitchActive(context)) return false
        AlertChannels.ensure(context)
        val arrow = GlucoseTrendArrow.fromRatePerMinute(currentRate)
        val id = customThresholdNotificationId(threshold.id)

        val metricText = when (threshold.kind) {
            CustomThreshold.Kind.VALUE -> "$currentValue mg/dL"
            CustomThreshold.Kind.RATE -> "${if ((metric ?: 0.0) > 0) "+" else ""}${"%.1f".format(Locale.US, metric ?: 0.0)} mg/dL/min"
        }

        val text = "Now $metricText — this alert can sound even during Silence/DND"
        val prefix = if (isInjected) DebugGlucoseOverride.TITLE_PREFIX else ""
        val bodyPrefix = if (isInjected) DebugGlucoseOverride.BODY_PREFIX else ""
        val fullDetail = if (isInjected) "${DebugGlucoseOverride.DISCLAIMER}\n$text" else text

        val builder = Notification.Builder(context, AlertChannels.currentCustomChannelId(context))
            .setGroup(AlertChannels.NOTIFICATION_GROUP_KEY)
            .setSmallIcon(NotificationIconFactory.readingIcon(context, currentValue, arrow))
            .setContentTitle("${prefix}🔔 ${threshold.displayLabel()}")
            .setContentText("$bodyPrefix$text")
            .setStyle(Notification.BigTextStyle().bigText(fullDetail))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, if (threshold.direction == CustomThreshold.Direction.FALLING) R.color.low else R.color.high))
            .setContentIntent(mainActivityIntent(context, REQ_CUSTOM_THRESHOLD_CONTENT))

        if (isInjected) {
            builder.setSubText(DebugGlucoseOverride.DISCLAIMER_SHORT)
        }

        val notification = builder.build()

        val posted = notifyIfAllowed(context) { nm -> nm.notify(id, notification) }
        if (posted) {
            val falling = threshold.direction == CustomThreshold.Direction.FALLING
            val tone = if (falling) AlertTones.Tone.WARN_LOW else AlertTones.Tone.WARN_HIGH
            AlertTones.play(context, tone, ignoreSilence = true)
            // Direct, channel-independent vibration guarantee (2026-09-22) - see
            // AlertTones.vibrate's own doc for the real incident (a threshold at 68 fired
            // silently on a Silent-mode phone). ignoreSilence=true matches the tone call above:
            // this tier already overrides Ahead's own in-app silence toggle, not just Android's
            // DND, so the vibration override must too. Pattern varies with direction, mirroring
            // the WARN_LOW/WARN_HIGH tone split right above it.
            AlertTones.vibrate(
                context,
                if (falling) AlertChannels.CUSTOM_THRESHOLD_FALLING_VIBRATION_PATTERN else AlertChannels.CUSTOM_THRESHOLD_RISING_VIBRATION_PATTERN,
                ignoreSilence = true,
            )
        }
        return posted
    }

    fun cancelCustomThreshold(context: Context, thresholdId: String) {
        NotificationManagerCompat.from(context).cancel(customThresholdNotificationId(thresholdId))
    }

    // Widened 2026-09-13 from 1000 to 100000 (ids now span 2500-102499,
    // still clear of every other fixed notification id in the app - the
    // ongoing status notification is 1001, red/yellow/plateau/correction are
    // 2001-2004) - shrinks the odds two distinct threshold UUIDs land on the
    // same hash-bucket and silently clobber each other's tray notification.
    private fun customThresholdNotificationId(thresholdId: String): Int =
        CUSTOM_THRESHOLD_ID_BASE + (Math.floorMod(thresholdId.hashCode(), CUSTOM_THRESHOLD_ID_RANGE))

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

    // Spoken wording for red/yellow glucose alerts (direction, signed rate, projection) lives in
    // SpokenAlertText - one tested place - rather than being built inline here.

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

    // Returns whether block() actually ran (permission granted) - only
    // showCustomThresholdAlert reads this return value today (it needs to
    // know whether to persist "fired" state - see that function's own doc);
    // every other caller here already ignored it and keeps ignoring it,
    // unaffected by the signature change.
    private inline fun notifyIfAllowed(context: Context, block: (NotificationManagerCompat) -> Unit): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            block(NotificationManagerCompat.from(context))
            return true
        }
        return false
    }
}
