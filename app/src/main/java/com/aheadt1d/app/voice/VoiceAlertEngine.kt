package com.aheadt1d.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Speaks alert text aloud via Android TextToSpeech, with per-severity voice
 * tuning (VoiceAlertCategory's pitch/rate) and transient audio-focus ducking so
 * anything already playing (music, podcast) drops in volume while we speak and
 * is restored afterward.
 *
 * Gating lives here, at the single speak() entry: the master toggle is checked
 * first, then the specific category toggle, and TTS is skipped entirely if
 * either is off. This is purely the voice layer - it never touches the visual
 * notification, DND bypass, or full-screen escalation, which fire independently
 * from AlertNotifier regardless of what voice is set to.
 */
object VoiceAlertEngine {
    private const val TAG = "VoiceAlertEngine"

    /**
     * Categories that ignore BOTH the master and per-category voice toggles.
     *
     * EMERGENCY: a critical-low siren must not be silenceable by a general
     * preference - that tier exists precisely for the case where every other
     * channel has failed.
     *
     * RED (2026-08-01): promoted here when the red-tier alert tone was
     * removed at the owner's request. Before that, sound carried red alerts
     * and voice was a bonus layer; now voice IS the audible half of "voice
     * and a buzz", so leaving it behind a toggle that was in fact switched
     * OFF at the time would have quietly reduced red alerts to vibration
     * only. A red alert is a dangerously low or high glucose reading, which
     * is not something a general "I don't want the app talking" preference
     * should be able to mute.
     *
     * Everything below red (yellow, plateau, correction, signal-lost) still
     * respects both toggles - those are informational tiers where the user's
     * stated preference should win.
     */
    private val UNGATED_CATEGORIES = setOf(VoiceAlertCategory.EMERGENCY, VoiceAlertCategory.RED)

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Safe to call more than once; initializes TTS on first call. Kicked off
     *  from AheadApplication.onCreate so the engine is warm before any alert. */
    fun init(context: Context) {
        if (tts != null) return
        val appContext = context.applicationContext
        audioManager = appContext.getSystemService(AudioManager::class.java)
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                tts?.setAudioAttributes(speechAttributes)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = abandonFocus()
                    @Deprecated("deprecated in API 21")
                    override fun onError(utteranceId: String?) = abandonFocus()
                    override fun onError(utteranceId: String?, errorCode: Int) = abandonFocus()
                })
            } else {
                Log.w(TAG, "TextToSpeech init failed (status=$status)")
            }
        }
    }

    /**
     * Speaks [text] for [category], if allowed. Returns without any TTS or audio
     * work when the master toggle or the category toggle is off - the gate is
     * evaluated before anything else touches the audio system.
     *
     * EMERGENCY and RED skip both gates entirely (see [UNGATED_CATEGORIES]).
     */
    fun speak(context: Context, category: VoiceAlertCategory, text: String) {
        if (category !in UNGATED_CATEGORIES) {
            if (!VoiceAlertPrefs.isMasterEnabled(context)) {
                Log.d(TAG, "Skipped $category: master voice toggle off")
                return
            }
            if (!VoiceAlertPrefs.isCategoryEnabled(context, category)) {
                Log.d(TAG, "Skipped $category: category toggle off")
                return
            }
        }

        val engine = tts
        if (engine == null || !ready) {
            // Cold start before TTS finished initializing - warm it for next time
            // and drop this one rather than blocking or queuing stale speech.
            Log.w(TAG, "TTS not ready; skipping $category utterance")
            init(context)
            return
        }

        engine.setPitch(category.pitch)
        engine.setSpeechRate(category.rate)
        requestFocus()

        Log.d(TAG, "Speaking $category: \"$text\"")
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "ahead-$category")
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "ahead-$category-${System.currentTimeMillis()}")
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes)
            .build()
        focusRequest = request
        am.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
