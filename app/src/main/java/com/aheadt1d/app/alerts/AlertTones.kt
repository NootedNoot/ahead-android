package com.aheadt1d.app.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RawRes
import com.aheadt1d.app.R

/**
 * Ahead's alert tone identity - synthesized, not sourced (pure-Python
 * sine/sweep generation, no external assets or licensing). Design language:
 *  - REGISTER encodes direction: low-side tones live in a lower pitch band,
 *    high-side in a higher one - low vs high is tellable by ear alone.
 *  - CONTOUR reinforces it: low-side sweeps DOWNWARD, high-side sweeps
 *    UPWARD, matching the number's own motion.
 *  - REPETITION/TEMPO encodes urgency: calm (plateau/correction) is one soft
 *    tone; warn (yellow) is one clear sweep; urgent (red) is three quick
 *    chirps.
 *  - SIGNAL_LOST is deliberately neither direction - an alternating
 *    "uncertain" wobble, since there's no confirmed reading to point at.
 *
 * Played directly via MediaPlayer rather than through a NotificationChannel's
 * sound attribute: decouples "which sound plays" from "which channel this
 * groups under", and for urgent/signal-lost specifically, USAGE_ALARM audio
 * attributes bypass DND independent of the fragile notification-policy-
 * access permission (see the incident note in `play()` below). Calm/warn
 * tones deliberately do NOT get that treatment - yellow-tier alerts are
 * meant to respect DND, not pierce it (see AlertChannels' own doc on that).
 *
 * AlertChannels explicitly silences the yellow/red channels
 * (setSound(null, null)) so this is the only thing that makes sound -
 * playing both would double up.
 */
object AlertTones {
    private const val TAG = "AlertTones"

    enum class Tone(@RawRes val res: Int, val urgent: Boolean) {
        CALM_LOW(R.raw.tone_calm_low, urgent = false),
        CALM_HIGH(R.raw.tone_calm_high, urgent = false),
        WARN_LOW(R.raw.tone_warn_low, urgent = false),
        WARN_HIGH(R.raw.tone_warn_high, urgent = false),
        // Currently unreferenced by any caller - red alerts play no tone at
        // all (see AlertNotifier.showRedAlert), so nothing currently
        // triggers these. Left defined rather than deleted since the raw
        // assets still exist and a future change to red's sound design
        // would likely reach for these first.
        URGENT_LOW(R.raw.tone_urgent_low, urgent = true),
        URGENT_HIGH(R.raw.tone_urgent_high, urgent = true),
        SIGNAL_LOST(R.raw.tone_signal_lost, urgent = true),
    }

    private val notificationAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // Alarm-usage attributes - reliably bypass DND in every mode except
    // "Total Silence", with no special permission needed. Only for the
    // urgent tier (red/signal-lost); calm/warn must keep respecting DND,
    // that's deliberate.
    private val alarmAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Single-shot, non-looping - these are for the ordinary alert tiers,
     *  which fire once per alert. Self-releases on completion/error. */
    fun play(context: Context, tone: Tone, ignoreSilence: Boolean = false) {
        val appContext = context.applicationContext
        if (!ignoreSilence && AlertSilenceManager.isSilenced(appContext)) {
            Log.d(TAG, "Skipping tone $tone - alerts silenced")
            return
        }
        if (ignoreSilence && AlertSilenceManager.isDevKillSwitchActive(appContext)) {
            Log.d(TAG, "Skipping tone $tone - dev kill switch active")
            return
        }
        // Urgent tones (red/signal-lost) force the alarm stream to max first
        // - USAGE_ALARM audio attributes alone only guarantee this isn't
        // BLOCKED by DND, not that it's audible; a low/zeroed alarm-stream
        // volume would otherwise play this silently even though every
        // permission and channel setting is correct. Real incident: a
        // correct, DND-bypassing notification that nobody actually heard.
        //
        // 2026-09-22: [ignoreSilence] now also picks the alarm stream, not just
        // WARN_LOW/WARN_HIGH's own non-urgent AudioAttributes. Real incident: a
        // custom threshold set for 68 (falling) fired a correctly-posted,
        // correctly-DND-bypassing-on-paper notification while the phone's
        // ringer was in Silent - no sound, no ping. Root cause traced on-device
        // (`dumpsys audio`): this phone's ringer-silent state mutes
        // STREAM_NOTIFICATION directly (`ringer mode affected streams` includes
        // STREAM_NOTIFICATION on this Samsung build), which is exactly the
        // stream WARN_LOW/WARN_HIGH were routed to via [notificationAttrs] -
        // completely independent of Do Not Disturb, and independent of whether
        // the channel's own setBypassDnd(true) actually stuck (see
        // AlertChannels' doc for why that flag can silently fail to apply).
        // STREAM_ALARM was NOT in that device's ringer-affected-streams list,
        // confirming [alarmAttrs] really is immune to this failure mode where
        // [notificationAttrs] is not. A caller passing ignoreSilence=true is
        // explicitly saying "this must be heard no matter what" - that already
        // implied alarm-stream routing; the two were just never wired together.
        if (tone.urgent || ignoreSilence) forceAlarmVolume(appContext)
        runCatching {
            val attrs = if (tone.urgent || ignoreSilence) alarmAttrs else notificationAttrs
            val player = MediaPlayer.create(appContext, tone.res, attrs, 0)
            if (player == null) {
                Log.w(TAG, "MediaPlayer.create returned null for $tone")
                return
            }
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { mp, _, _ -> runCatching { mp.release() }; true }
            player.start()
        }.onFailure { Log.w(TAG, "couldn't play $tone", it) }
    }

    /**
     * A direct [Vibrator] call, completely independent of any NotificationChannel's own
     * vibration - added 2026-09-22 as the vibration equivalent of [alarmAttrs] above, for the
     * exact same reason: a channel's vibration is only as reliable as that channel's settings
     * actually being what the code asked for, and [AlertChannels]' own doc documents a real,
     * confirmed way that can silently fail (setBypassDnd only sticks if Notification Policy
     * Access was already granted at channel-CREATION time; a phone that never granted it keeps
     * a channel that never bypasses anything, indefinitely, with nothing in the UI forcing the
     * user to notice). This call needs no such permission at all: `AudioAttributes.USAGE_ALARM`
     * routes to the alarm vibration path the same way it routes MediaPlayer to the alarm stream
     * above - the same real incident (a Silent-mode phone, a correctly-configured custom
     * threshold, zero perceptible alert) is what motivated adding this, not just the tone fix,
     * since a phone with its ringer silenced but not muted-notifications-only could just as
     * easily have had a working speaker and a broken/never-granted vibration channel instead.
     *
     * Deliberately redundant with the channel's own vibration (same philosophy as the channel
     * sound + AlertTones tone redundancy above) - this can only ever add a second buzz, never
     * replace a working one.
     */
    fun vibrate(context: Context, pattern: LongArray, ignoreSilence: Boolean = false) {
        val appContext = context.applicationContext
        if (!ignoreSilence && AlertSilenceManager.isSilenced(appContext)) {
            Log.d(TAG, "Skipping direct vibration - alerts silenced")
            return
        }
        if (ignoreSilence && AlertSilenceManager.isDevKillSwitchActive(appContext)) {
            Log.d(TAG, "Skipping direct vibration - dev kill switch active")
            return
        }
        runCatching {
            @Suppress("DEPRECATION") // Vibrator via getSystemService still works on every
            // supported API level (28+); VibratorManager (API 31+) only matters for
            // multi-vibrator devices, which this app has no need to distinguish.
            val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(pattern, -1)
            vibrator.vibrate(effect, alarmAttrs)
        }.onFailure { Log.w(TAG, "couldn't run direct vibration", it) }
    }

    private fun forceAlarmVolume(context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        }.onFailure { Log.w(TAG, "couldn't force alarm volume", it) }
    }
}
