package com.aheadt1d.app.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.content.edit

/**
 * Owns the two alerting channels, kept separate from the silent ongoing
 * status channel in GlucoseNotifier:
 *
 *  - Yellow: high-importance. Never escalates - no DND bypass, no
 *    full-screen intent, nothing lock-screen-public. That's by construction:
 *    the escalation features only exist on the red path.
 *  - Red: high-importance, setBypassDnd(true) so the notification itself
 *    (heads-up, vibration) pierces Do Not Disturb.
 *
 * Both channels ALSO carry a real default sound (2026-08-01), not just
 * AlertTones' direct MediaPlayer playback - deliberately redundant. A real
 * incident showed the app posting a correct, DND-bypassing red notification
 * with nobody hearing or feeling it; AlertTones' custom playback has no
 * fallback if MediaPlayer.create() fails, the alarm stream happens to be
 * low, or anything else in that one code path goes wrong. The channel's own
 * OS-managed sound is a second, independent path to the same outcome - yes,
 * this means both may audibly play together, which is a deliberately
 * accepted redundant-noise cost against the alternative (silence).
 *
 * BOTH channel ids are versioned (glucose_alerts_active_vN /
 * glucose_alerts_yellow_vN), because Android channels are immutable to the
 * app once created AND deleting + recreating the SAME id resurrects the old
 * settings instead of applying the new ones (anti-abuse - learned the hard
 * way migrating yellow off its old default sound: a straight delete+recreate
 * with the same literal id silently kept the old sound). The only way to
 * actually change a channel's settings post-creation is a fresh id: create
 * the new one first (never a moment with no channel), delete the old one,
 * persist the new id.
 *
 * Red's migration additionally triggers on a bypassDnd mismatch: the flag
 * only sticks if the app holds Notification Policy Access at the moment the
 * channel is CREATED, so when policy access is granted after the channel
 * already exists without bypass, this is what fixes it. If the user later
 * revokes policy access, the existing channel keeps whatever bypass flag it
 * has; there is no downward migration.
 *
 * Separately, [dndAccessRegressed] tracks whether policy access was ever
 * observed granted and has since gone missing - see its doc for why a
 * REGRESSION (as opposed to never having granted it) is what's worth
 * surfacing to the user.
 */
object AlertChannels {
    // Shared by GlucoseNotifier (the persistent status notification) and
    // every AlertNotifier tier (red/yellow/plateau/correction/custom
    // threshold/signal-lost) - 2026-09-13, at the owner's request, so
    // several Ahead notifications sitting in the shade at once visually
    // cluster together (a single expandable group) instead of interleaving
    // with other apps' notifications, one-by-one, with no indication
    // they're related. See GlucoseNotifier's own doc for the other half of
    // this fix (a constant, non-severity-varying marker so the ongoing
    // notification's title can never visually match an alert's).
    const val NOTIFICATION_GROUP_KEY = "ahead_notifications"

    // Shared with AlertNotifier's new (2026-09-22) direct AlertTones.vibrate() calls, so the
    // channel's own vibration and the redundant direct one are always the same pattern - never
    // two independently-declared literals that can quietly drift apart. Red's pattern also covers
    // signal-lost, which deliberately shares red's notification id/channel identity.
    val RED_VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400, 200, 600)
    val CUSTOM_THRESHOLD_VIBRATION_PATTERN = longArrayOf(0, 200, 100, 200, 100, 200, 100, 200)

    private const val PREFS_NAME = "ahead_alert_channels"
    private const val KEY_RED_CHANNEL_ID = "red_channel_id"
    private const val KEY_YELLOW_CHANNEL_ID = "yellow_channel_id"
    private const val KEY_CUSTOM_CHANNEL_ID = "custom_threshold_channel_id"
    private const val KEY_DND_EVER_GRANTED = "dnd_ever_granted"
    private const val KEY_SOUND_SCHEME_VERSION = "sound_scheme_version"
    private const val DEFAULT_RED_CHANNEL_ID = "glucose_alerts_active"
    private const val DEFAULT_YELLOW_CHANNEL_ID = "glucose_alerts_yellow"
    private const val DEFAULT_CUSTOM_CHANNEL_ID = "glucose_alerts_custom_threshold"
    private const val TAG = "AlertChannels"

    // 2026-07-31: bumped when alert sound moved from each channel's own
    // sound attribute to AlertTones' direct playback. A channel created
    // under the OLD scheme still has a baked-in sound that would now play
    // ALONGSIDE the new direct tone, doubling up - any install below this
    // version gets both channels migrated to a fresh id once, silent.
    // v2->v3: the first pass at this migration deleted+recreated yellow
    // using the SAME literal id, which - like red's already-documented
    // anti-abuse quirk - resurrected the old sound instead of clearing it,
    // while still marking the migration complete. v3 uses a versioned id
    // for yellow too (see ensure()) and re-runs for anyone who landed on v2.
    // v3->v4 (2026-08-01): restored a real channel-level sound as a
    // redundant fallback under AlertTones (see the class doc) - the
    // opposite direction of the v2->v3 migration, so this also drops the
    // old "only migrate if channel.sound != null" guard in ensure() below
    // in favor of a plain version check, since that guard was written
    // assuming migration only ever meant "clear the sound."
    // v4->v5 (2026-08-01): yellow now carries an explicit vibration
    // pattern - enableVibration(true) alone fell back to the OS default,
    // a single short pulse easy to miss compared to red's distinct
    // three-pulse pattern.
    // v5->v6 (2026-08-01): red goes silent again at the owner's request -
    // that tier is now vibration + ungated voice, no tone (see
    // buildRedChannel). Yellow keeps its sound; only red changed, but the
    // version gate is shared so both channels re-migrate.
    // v6->v7 (2026-09-22): custom-threshold's channel sound moved from
    // USAGE_NOTIFICATION to USAGE_ALARM (see buildCustomThresholdChannel's own
    // doc for the real incident - a threshold fired silently on a phone whose
    // ringer-silent state muted STREAM_NOTIFICATION directly, independent of
    // DND). This version gate is shared across all three channels, so red and
    // yellow also re-migrate even though neither's own settings changed this
    // time - harmless (idempotent, one-shot) and keeps a single version
    // counter instead of a third parallel one.
    private const val SOUND_SCHEME_VERSION = 7

    fun currentRedChannelId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RED_CHANNEL_ID, DEFAULT_RED_CHANNEL_ID) ?: DEFAULT_RED_CHANNEL_ID

    fun currentYellowChannelId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_YELLOW_CHANNEL_ID, DEFAULT_YELLOW_CHANNEL_ID) ?: DEFAULT_YELLOW_CHANNEL_ID

    /** Custom-threshold overrides (2026-09-13): bypasses DND like red, but
     *  carries a real sound like yellow - see buildCustomThresholdChannel's
     *  own doc for why this is a third, distinct tier rather than reusing
     *  either existing channel. */
    fun currentCustomChannelId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_CHANNEL_ID, DEFAULT_CUSTOM_CHANNEL_ID) ?: DEFAULT_CUSTOM_CHANNEL_ID

    /** Idempotent and cheap - safe to call from Application.onCreate, before
     *  every alert post, and after returning from the DND-access settings
     *  screen (that last one is what actually triggers the DND migration). */
    fun ensure(context: Context) {
        context.getSystemService(NotificationManager::class.java).let { quietNm ->
            if (quietNm.getNotificationChannel(QUIET_CHANNEL_ID) == null) {
                quietNm.createNotificationChannel(buildQuietChannel())
            }
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Latches "this device has had DND access granted at least once" -
        // read by dndAccessRegressed below. ensure() runs often enough (app
        // start, every alert, return-from-settings) that this stays current
        // without any dedicated polling.
        if (nm.isNotificationPolicyAccessGranted) {
            prefs.edit { putBoolean(KEY_DND_EVER_GRANTED, true) }
        }

        val needsSoundMigration = prefs.getInt(KEY_SOUND_SCHEME_VERSION, 1) < SOUND_SCHEME_VERSION

        val yellowId = currentYellowChannelId(context)
        if (nm.getNotificationChannel(yellowId) == null) {
            nm.createNotificationChannel(buildYellowChannel(yellowId))
        } else if (needsSoundMigration) {
            val newId = nextVersionedId(yellowId, DEFAULT_YELLOW_CHANNEL_ID)
            nm.createNotificationChannel(buildYellowChannel(newId))
            nm.deleteNotificationChannel(yellowId)
            prefs.edit { putString(KEY_YELLOW_CHANNEL_ID, newId) }
        }

        val redId = currentRedChannelId(context)
        if (nm.getNotificationChannel(redId) == null) {
            nm.createNotificationChannel(buildRedChannel(redId))
        }

        val redChannel = nm.getNotificationChannel(redId) ?: return
        val redNeedsDndMigration = !redChannel.canBypassDnd() && nm.isNotificationPolicyAccessGranted
        if (redNeedsDndMigration || needsSoundMigration) {
            val newId = nextVersionedId(redId, DEFAULT_RED_CHANNEL_ID)
            nm.createNotificationChannel(buildRedChannel(newId))
            nm.deleteNotificationChannel(redId)
            prefs.edit { putString(KEY_RED_CHANNEL_ID, newId) }
            if (nm.getNotificationChannel(newId)?.canBypassDnd() != true) {
                Log.w(TAG, "Red channel $newId still can't bypass DND despite policy access")
            }
        }

        if (needsSoundMigration) {
            prefs.edit { putInt(KEY_SOUND_SCHEME_VERSION, SOUND_SCHEME_VERSION) }
        }

        // Custom-threshold channel: migrates on the DND-bypass-granted-late
        // check red also needs, AND now (2026-09-22) on needsSoundMigration too
        // - joined onto the shared SOUND_SCHEME_VERSION gate specifically so
        // an existing install (a channel created back when its sound was still
        // USAGE_NOTIFICATION) actually picks up the USAGE_ALARM fix. Without
        // this, the code change alone does nothing for anyone who already had
        // the channel - see buildCustomThresholdChannel's own doc for why.
        val customId = currentCustomChannelId(context)
        if (nm.getNotificationChannel(customId) == null) {
            nm.createNotificationChannel(buildCustomThresholdChannel(customId))
        } else {
            val customChannel = nm.getNotificationChannel(customId)
            val customNeedsDndMigration = customChannel != null && !customChannel.canBypassDnd() && nm.isNotificationPolicyAccessGranted
            if (customChannel != null && (customNeedsDndMigration || needsSoundMigration)) {
                val newId = nextVersionedId(customId, DEFAULT_CUSTOM_CHANNEL_ID)
                nm.createNotificationChannel(buildCustomThresholdChannel(newId))
                nm.deleteNotificationChannel(customId)
                prefs.edit { putString(KEY_CUSTOM_CHANNEL_ID, newId) }
            }
        }
    }

    /**
     * Silent channel for notifications that must be VISIBLE without interrupting - added
     * 2026-09-20 for two cases: a data-blackout notice shown while the person has alerts
     * silenced, and re-posting a red the person dismissed while still held inside the low band.
     *
     * It has to be a separate channel rather than a builder flag: from API 26 the CHANNEL owns
     * sound and vibration, so Notification.Builder cannot mute a post made on the high-importance
     * red channel. IMPORTANCE_LOW means no sound, no vibration, no heads-up - the notification
     * simply appears in the shade, which is exactly the intent.
     */
    const val QUIET_CHANNEL_ID = "glucose_quiet_notice"

    private fun buildQuietChannel(): NotificationChannel =
        NotificationChannel(QUIET_CHANNEL_ID, "Silent notices", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Visible-but-silent notices, e.g. no-data while alerts are silenced"
            enableVibration(false)
            setSound(null, null)
            setBypassDnd(false)
        }

    private fun buildYellowChannel(id: String): NotificationChannel =
        NotificationChannel(id, "Glucose warnings", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Early warnings when glucose is trending out of range"
            enableVibration(true)
            // 2026-08-01: explicit pattern, not just enableVibration(true) -
            // that alone left the OS default (a single short, easy-to-miss
            // buzz on this device). Two clear pulses, distinct from red's
            // three-pulse pattern below, so severity is tellable by feel
            // alone without looking at the phone.
            vibrationPattern = longArrayOf(0, 300, 150, 300)
            // Respects DND (no ALARM usage here, matching the channel not
            // bypassing DND) - a real fallback sound, not silent, but yellow
            // deliberately stays a quieter tier than red.
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }

    private fun buildRedChannel(id: String): NotificationChannel =
        NotificationChannel(id, "Glucose red alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Urgent alerts when glucose is dangerously low or high"
            setBypassDnd(true)
            enableVibration(true)
            vibrationPattern = RED_VIBRATION_PATTERN
            // Silent by design as of 2026-08-01 - red alerts are vibration +
            // (ungated) voice, no tone. See AlertNotifier.showRedAlert for
            // the reasoning. Note this channel still sets bypassDnd and a
            // vibration pattern: "no sound" is not "no interruption", and the
            // buzz still has to pierce Do Not Disturb.
            //
            // Deliberately NOT restoring the fallback alarm sound that was
            // added here earlier the same day - that existed to guarantee red
            // was never silent, which is no longer the goal for this tier.
            // (The critical-low siren that once covered the genuinely
            // critical case was removed 2026-08-20, at the owner's request -
            // there's nothing left standing in for it.)
            setSound(null, null)
        }

    /**
     * Custom user-defined thresholds (2026-09-13, see CustomThresholdCoordinator):
     * bypasses DND like red, but - unlike red - carries a real, distinct
     * sound, because the whole point of this tier is "punch through silence
     * for the one thing I specifically asked to be told about," and a silent
     * vibrate-only alert wouldn't reliably do that. Deliberately NOT the old
     * CriticalLowSiren's forced-volume approach (removed 2026-08-20, "an
     * alarm that couldn't be dismissed") - it doesn't force the device's
     * volume up the way [AlertTones.forceAlarmVolume] does for red/
     * signal-lost, and it overrides Ahead's own in-app silence killswitch
     * (see AlertNotifier.showCustomThresholdAlert, which deliberately skips
     * the AlertSilenceManager.isSilenced() gate every other alert function
     * checks first) - but ONLY the one notification CustomThresholdCoordinator
     * actually decides to post (a fresh crossing or a further escalation),
     * never a standing "always loud" state.
     *
     * 2026-09-22: sound AudioAttributes changed from USAGE_NOTIFICATION to
     * USAGE_ALARM. Real incident: a threshold set at 68 (falling) fired a
     * correctly-posted notification while the phone's ringer was in Silent -
     * no sound, no vibration felt. Traced on-device: this phone's
     * ringer-silent state mutes STREAM_NOTIFICATION directly (independent of
     * Do Not Disturb, and independent of whether setBypassDnd actually stuck -
     * see this file's class doc on why that flag can silently fail to apply),
     * which is exactly the stream this channel's sound used to route through.
     * STREAM_ALARM is not muted by ringer-silent on that same device. Matches
     * AlertTones.play()'s identical 2026-09-22 fix for the direct-MediaPlayer
     * path, and AlertTones.vibrate() (new the same day) adds a
     * channel-independent vibration guarantee on top, for the same reason
     * this channel's own vibration alone wasn't enough - see that function's
     * doc for the full reasoning.
     */
    private fun buildCustomThresholdChannel(id: String): NotificationChannel =
        NotificationChannel(id, "Custom glucose thresholds", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Your own rate/value tripwires - can sound even during Silence or Do Not Disturb"
            setBypassDnd(true)
            enableVibration(true)
            // A third, distinct pattern from yellow's two-pulse and red's
            // three-pulse, so this tier is tellable by feel alone too.
            vibrationPattern = CUSTOM_THRESHOLD_VIBRATION_PATTERN
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }

    private fun nextVersionedId(currentId: String, baseId: String): String {
        val version = Regex("_v(\\d+)$").find(currentId)?.groupValues?.get(1)?.toIntOrNull()
        return "${baseId}_v${(version ?: 1) + 1}"
    }

    /**
     * True when DND access was observed granted at some point (per the latch
     * in [ensure]) but is NOT granted right now - i.e. it REGRESSED, as
     * distinct from never having been granted. A user who consciously skipped
     * the setup wizard's DND step made that choice already; nagging them on
     * every home-screen visit would just be alarm fatigue for a non-change.
     * A user who granted it and then had it silently revoked (a "clean up
     * permissions" system prompt, an OEM battery/permission auto-revoke,
     * manually toggling it off) is the case actually worth surfacing, since
     * it means a red alert could arrive muted with no warning.
     */
    fun dndAccessRegressed(context: Context): Boolean {
        val everGranted = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DND_EVER_GRANTED, false)
        if (!everGranted) return false
        val nm = context.getSystemService(NotificationManager::class.java)
        return !nm.isNotificationPolicyAccessGranted
    }
}
