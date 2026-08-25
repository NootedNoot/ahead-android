package com.aheadt1d.app.events

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-tagged annotation against the glucose timeline (stress, pod issue,
 * site change, illness, exercise, correction, insulin, or a free-form note).
 * Pure logging for every tag except CORRECTION and INSULIN - nothing here
 * feeds AI guesses, alerts, or any dosing-adjacent logic beyond those two.
 * CORRECTION is a narrow, explicit exception: logging one opens a
 * correction-response tracking window (see PlateauCoordinator.
 * onCorrectionLogged, hooked in EventLogDialogs) - never a dose value or
 * dosing guidance, just a timestamp the alert engine can check back against.
 * INSULIN (2026-08-25) is the same shape of narrow exception, one level
 * further: purely a timestamp (no dose amount - deliberately out of scope,
 * see UserEventRepository.mostRecentInsulinTimestamp's doc), read by
 * GlucoseCheckRunner to populate the backend's minutesSinceLastBolus field,
 * which unlocks guess-engine.js's bolus-dependent guesses. Distinct from
 * CORRECTION on purpose: a correction dose (fixing an already-high reading)
 * and a meal-time bolus (proactive, unrelated to a correction) are different
 * events the backend's guesses need to reason about differently - conflating
 * them into one tag would blur that distinction, not simplify it.
 */
@Entity(tableName = "user_events")
data class UserEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val tag: String,
    val note: String? = null,
    val glucoseAtTime: Float? = null
)

/** The presets from the spec, plus the free-form escape hatch. Stored in
 *  UserEvent.tag as [storageValue] so renaming a [label] later doesn't orphan
 *  old rows. */
enum class EventTag(val storageValue: String, val label: String, val glyph: String) {
    STRESS("stress", "Stress", "😰"),
    POD_ISSUE("pod_issue", "Pod Issue", "🔧"),
    SITE_CHANGE("site_change", "Site Change", "🩹"),
    ILLNESS("illness", "Illness", "🤒"),
    EXERCISE("exercise", "Exercise", "🏃"),
    CORRECTION("correction", "Correction", "💉"),
    INSULIN("insulin", "Insulin", "💉"),
    OTHER("other", "Other", "📝");

    companion object {
        fun fromStorageValue(value: String): EventTag =
            entries.firstOrNull { it.storageValue == value } ?: OTHER
    }
}
