package com.aheadt1d.app.voice

/**
 * The alert types that can be spoken aloud, each carrying its own TTS voice
 * tuning. RED is faster and higher-pitched to read as urgent. Each maps 1:1
 * to a user-facing toggle in the Voice Alerts settings and to one of
 * AlertNotifier's fire-points.
 *
 * SIGNAL_LOST shares RED's urgent profile (2026-07-27, previously its own
 * calmer 0.95f/0.95f): a total data blackout is now delivered as a genuine
 * red-severity alert (see AlertNotifier.showSignalLostAlert), so its voice
 * should read with the same urgency, not the softer "attention-please" tone
 * that fit its old yellow-tier treatment. Kept as its own category (rather
 * than reusing VoiceAlertCategory.RED directly) so the dedicated
 * "Speak signal-lost alerts" toggle stays meaningful - a user may reasonably
 * want one on and the other off.
 */
enum class VoiceAlertCategory(val pitch: Float, val rate: Float) {
    RED(pitch = 1.1f, rate = 1.15f),
    YELLOW(pitch = 1.0f, rate = 1.0f),
    SIGNAL_LOST(pitch = 1.1f, rate = 1.15f),
    // Calm profile: an attention-please about a sustained state, not an
    // in-the-moment emergency - unlike SIGNAL_LOST above, these two were not
    // reclassified.
    PLATEAU(pitch = 0.95f, rate = 0.95f),
    CORRECTION(pitch = 0.95f, rate = 0.95f),
}
