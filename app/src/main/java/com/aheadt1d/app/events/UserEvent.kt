package com.aheadt1d.app.events

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-tagged annotation against the glucose timeline (stress, pod issue,
 * site change, illness, exercise, correction, or a free-form note). Pure
 * logging for every tag except CORRECTION - nothing here feeds AI guesses,
 * alerts, or any dosing-adjacent logic. CORRECTION is a narrow, explicit
 * exception: logging one opens a correction-response tracking window (see
 * PlateauCoordinator.onCorrectionLogged, hooked in EventLogDialogs) - never a
 * dose value or dosing guidance, just a timestamp the alert engine can check
 * back against.
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
    OTHER("other", "Other", "📝");

    companion object {
        fun fromStorageValue(value: String): EventTag =
            entries.firstOrNull { it.storageValue == value } ?: OTHER
    }
}
