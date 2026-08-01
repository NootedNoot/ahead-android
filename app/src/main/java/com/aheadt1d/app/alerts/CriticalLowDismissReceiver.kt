package com.aheadt1d.app.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when the critical-low notification is swiped away from the shade
 * (Notification.Builder.setDeleteIntent) - treated exactly like tapping "I'm
 * treating this" on the takeover screen: stops the whole siren loop.
 * Without this, swiping the notification away would just clear it from the
 * shade while sound/vibration/voice kept repeating every ~25s with nothing
 * visible left to explain why - a swipe has to mean "I've got this", same as
 * the in-app button, not just "get this out of my notifications".
 *
 * "Exactly like" is load-bearing: this must tear down BOTH escalation
 * timers, the same pair RedAlertActivity's dismiss button does. A low
 * episode arms two independent emergency-contact timers - the siren's own
 * 10-minute one (CriticalLowEmergencyScheduler, cancelled by
 * CriticalLowSiren.stop) and AlertCoordinator's ordinary 15-minute one
 * (EmergencyAlertScheduler, cancelled via AlertNotifier.cancelRed). The
 * 2026-08-01 audit found this receiver only cancelled the first, so swiping
 * the notification - the gesture the notification text itself invites, and
 * the fastest one to reach while low and shaky - still let a real SMS reach
 * a real emergency contact 15 minutes later, for an episode already handled.
 */
class CriticalLowDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISSED) return
        CriticalLowSiren.stop(context)
        AlertNotifier.cancelRed(context)
    }

    companion object {
        const val ACTION_DISMISSED = "com.aheadt1d.app.action.CRITICAL_LOW_NOTIFICATION_DISMISSED"
    }
}
