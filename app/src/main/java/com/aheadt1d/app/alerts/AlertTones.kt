package com.aheadt1d.app.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
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
    fun play(context: Context, tone: Tone) {
        val appContext = context.applicationContext
        if (AlertSilenceManager.isSilenced(appContext)) {
            Log.d(TAG, "Skipping tone $tone - alerts silenced")
            return
        }
        // Urgent tones (red/signal-lost) force the alarm stream to max first
        // - USAGE_ALARM audio attributes alone only guarantee this isn't
        // BLOCKED by DND, not that it's audible; a low/zeroed alarm-stream
        // volume would otherwise play this silently even though every
        // permission and channel setting is correct. Real incident: a
        // correct, DND-bypassing notification that nobody actually heard.
        if (tone.urgent) forceAlarmVolume(appContext)
        runCatching {
            val player = MediaPlayer.create(appContext, tone.res)
            if (player == null) {
                Log.w(TAG, "MediaPlayer.create returned null for $tone")
                return
            }
            player.setAudioAttributes(if (tone.urgent) alarmAttrs else notificationAttrs)
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { mp, _, _ -> runCatching { mp.release() }; true }
            player.start()
        }.onFailure { Log.w(TAG, "couldn't play $tone", it) }
    }

    private fun forceAlarmVolume(context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        }.onFailure { Log.w(TAG, "couldn't force alarm volume", it) }
    }
}
