package com.aheadt1d.app.voice

/**
 * The alert types that can be spoken aloud, each carrying its own TTS voice
 * tuning. RED is faster and higher-pitched to read as urgent; the signal-lost
 * "no data" voice is deliberately slower and calmer, since it's an
 * attention-please, not an emergency. Each maps 1:1 to a user-facing toggle in
 * the Voice Alerts settings and to one of AlertNotifier's fire-points.
 */
enum class VoiceAlertCategory(val pitch: Float, val rate: Float) {
    RED(pitch = 1.1f, rate = 1.15f),
    YELLOW(pitch = 1.0f, rate = 1.0f),
    SIGNAL_LOST(pitch = 0.95f, rate = 0.95f),
    // Same calm profile as SIGNAL_LOST - an attention-please about a
    // sustained state, not an in-the-moment emergency.
    PLATEAU(pitch = 0.95f, rate = 0.95f),
    CORRECTION(pitch = 0.95f, rate = 0.95f),
}
