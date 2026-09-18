package com.aheadt1d.app.alerts

import android.content.Context
import androidx.core.content.edit
import com.aheadt1d.app.voice.VoiceAlertEngine

/**
 * Killswitch and temporary silence manager for all Ahead alerts.
 * When active, completely silences all tones, vibrations, voice alerts,
 * and interrupting notifications for 10, 15, 30, or 60 minutes.
 *
 * Two independent layers live here, deliberately NOT merged into one flag:
 *
 * 1. Ordinary silence ([silence]/[silenceIndefinitely]/[isSilenced] below) -
 *    what every existing alert (red/yellow/plateau/correction/signal-lost,
 *    tones, voice) already respects. CustomThresholdCoordinator's whole
 *    reason for existing is that a user-defined threshold can punch THROUGH
 *    this layer on a fresh crossing - see AlertNotifier.showCustomThresholdAlert,
 *    which deliberately does not call [isSilenced].
 * 2. [isDevKillSwitchActive] (2026-09-13, added at the owner's explicit
 *    request right after the custom-threshold feature shipped: "I need a
 *    dev silence that kills everything regardless of what the User feature
 *    is for, just in case") - a strictly higher-privilege override that
 *    beats EVERYTHING, including a custom threshold's own DND/silence
 *    override. [isSilenced] folds this in via OR, so every existing caller
 *    of it (all the alert tiers, AlertTones, VoiceAlertEngine) is covered
 *    with zero changes to them; [showCustomThresholdAlert] additionally
 *    checks [isDevKillSwitchActive] directly, since it's the one function
 *    that intentionally does not call the general [isSilenced].
 *
 * Deliberately NO auto-expiry on the kill switch (matches [silenceIndefinitely]'s
 * existing no-auto-expiry precedent) - "just in case" reads as a deliberate,
 * hands-on action, not a timer. That does mean it's the one silence mode in
 * this app that can be forgotten indefinitely, which is exactly why it's
 * surfaced loudly and separately everywhere silence status is shown (the
 * persistent status notification, the main dashboard banner, the silence
 * dialog) rather than folded quietly into the ordinary "Silenced" copy.
 */
object AlertSilenceManager {
    private const val PREFS_NAME = "ahead_alert_silence"
    private const val KEY_SILENCED_UNTIL_MS = "silenced_until_epoch_ms"
    private const val KEY_DEV_KILL_SWITCH_ACTIVE = "dev_kill_switch_active"

    const val SILENCE_INDEFINITE = Long.MAX_VALUE

    fun silence(context: Context, minutes: Int) {
        if (minutes <= 0) {
            silenceIndefinitely(context)
            return
        }
        val untilMs = System.currentTimeMillis() + (minutes * 60_000L)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(KEY_SILENCED_UNTIL_MS, untilMs)
        }
        cancelActiveAlerts(context)
    }

    fun silenceIndefinitely(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(KEY_SILENCED_UNTIL_MS, SILENCE_INDEFINITE)
        }
        cancelActiveAlerts(context)
    }

    private fun cancelActiveAlerts(context: Context) {
        // Cancel all interrupting notifications immediately
        AlertNotifier.cancelRed(context)
        AlertNotifier.cancelYellow(context)
        AlertNotifier.cancelPlateau(context)
        AlertNotifier.cancelCorrection(context)
        // Tray tidiness only - does NOT touch CustomThreshold.currentlyCrossed
        // in storage, so a threshold that's still genuinely crossed won't
        // spuriously re-fire as "fresh" the instant silence is invoked; it
        // simply won't re-punch-through until it either recovers and crosses
        // again, or escalates meaningfully further (CustomThresholdMath).
        CustomThresholdStore.load(context).forEach { AlertNotifier.cancelCustomThreshold(context, it.id) }
        VoiceAlertEngine.stop()
    }

    fun cancelSilence(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(KEY_SILENCED_UNTIL_MS, 0L)
        }
    }

    /**
     * The dev kill switch - see the class doc for why this is a separate
     * flag from ordinary silence, not just another duration option. Turning
     * it ON also runs [cancelActiveAlerts] (same tray cleanup ordinary
     * silence does); turning it OFF is deliberately one plain write with no
     * side effects, so re-enabling alerts is never harder than disabling
     * them was.
     */
    fun isDevKillSwitchActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEV_KILL_SWITCH_ACTIVE, false)

    fun setDevKillSwitch(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_DEV_KILL_SWITCH_ACTIVE, active)
        }
        if (active) cancelActiveAlerts(context)
    }

    /** True while EITHER ordinary silence or the dev kill switch is active -
     *  see the class doc. [showCustomThresholdAlert] is the one caller that
     *  needs to tell these apart, so it checks [isDevKillSwitchActive]
     *  directly instead of this. */
    fun isSilenced(context: Context): Boolean {
        if (isDevKillSwitchActive(context)) return true
        val untilMs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SILENCED_UNTIL_MS, 0L)
        if (untilMs == SILENCE_INDEFINITE) return true
        return System.currentTimeMillis() < untilMs
    }

    // Folds in isDevKillSwitchActive the same way isSilenced does (2026-09-13
    // fix): before this, a caller reading isPermanentlySilenced/
    // getRemainingMinutes directly (MainActivity's showSilenceDialog/
    // updateSilenceUI do exactly this) would see isSilenced()=true (correctly,
    // via the kill switch) but isPermanent=false and remaining=0 - an
    // incoherent "silenced but not permanent and 0 minutes left" state that
    // doesn't describe either real mode. Treating the kill switch as
    // permanent here keeps every caller of these three functions consistent
    // without each one needing its own separate kill-switch check.
    fun isPermanentlySilenced(context: Context): Boolean {
        if (isDevKillSwitchActive(context)) return true
        val untilMs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SILENCED_UNTIL_MS, 0L)
        return untilMs == SILENCE_INDEFINITE
    }

    /**
     * Remaining silence duration in whole minutes, or -1 if silenced indefinitely
     * (including via the dev kill switch, which never expires), or 0 if not
     * currently silenced.
     */
    fun getRemainingMinutes(context: Context): Int {
        if (isDevKillSwitchActive(context)) return -1
        val untilMs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SILENCED_UNTIL_MS, 0L)
        if (untilMs == SILENCE_INDEFINITE) return -1
        val remainingMs = untilMs - System.currentTimeMillis()
        if (remainingMs <= 0) return 0
        return ((remainingMs + 59_999L) / 60_000L).toInt()
    }

    fun getSilenceDescription(context: Context): String {
        if (isDevKillSwitchActive(context)) return "Dev kill switch active — disable it from the Debug Menu"
        if (!isSilenced(context)) return "Alerts Active (Normal)"
        if (isPermanentlySilenced(context)) return "Silenced until cancelled"
        val rem = getRemainingMinutes(context)
        return "Silenced (${rem}m remaining)"
    }
}
