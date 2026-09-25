package com.aheadt1d.app.tutorial

data class TutorialStep(
    val readingIndex: Int,
    val timeMinutes: Int,
    val value: Int,
    val ratePerMinute: Double,
    val deltaFromPrevious: Int?,
    val projected15m: Int?,
    val projected30m: Int?,
    val severity: String,
    val alertFired: Boolean,
    val stepTitle: String,
    val coachingHeader: String,
    val coachingBody: String,
    val aheadRuleOfThumb: String,
    val userActionPrompt: String,
)

data class TutorialScenario(
    val id: String,
    val title: String,
    val badge: String,
    val description: String,
    val steps: List<TutorialStep>,
)

object TutorialRepository {
    val scenarios: List<TutorialScenario> = listOf(
        TutorialScenario(
            id = "drop_yellow_hold_horses",
            title = "Hold Your Horses (Yellow Alert)",
            badge = "REAL DATA",
            description = "Tonight's exact scenario: Fast drop at 132 mg/dL. See why waiting 2–3 pings avoids a 250+ spike.",
            steps = listOf(
                TutorialStep(
                    readingIndex = 0,
                    timeMinutes = 0,
                    value = 141,
                    ratePerMinute = -0.6,
                    deltaFromPrevious = -2,
                    projected15m = 132,
                    projected30m = 123,
                    severity = "none",
                    alertFired = false,
                    stepTitle = "Baseline (0m) — 141 mg/dL",
                    coachingHeader = "Cruising Comfortably in Target",
                    coachingBody = "Glucose is comfortable at 141 mg/dL, drifting gently down at -0.6 mg/dL/min. Ahead displays a green status with no active alerts.",
                    aheadRuleOfThumb = "Normal status: Your current trajectory is well within safe bounds.",
                    userActionPrompt = "No action required. Continue your normal evening."
                ),
                TutorialStep(
                    readingIndex = 1,
                    timeMinutes = 5,
                    value = 132,
                    ratePerMinute = -1.8,
                    deltaFromPrevious = -9,
                    projected15m = 105,
                    projected30m = 78,
                    severity = "yellow",
                    alertFired = true,
                    stepTitle = "Ping #1 (+5m) — 132 ↘ [Yellow Alert!]",
                    coachingHeader = "The 1st Alert: Yellow Heads-Up",
                    coachingBody = "Ahead sounds a Yellow Alert: 132 mg/dL ↘ (-9 mg/dL). The drop rate jumped to -1.8/min, projecting 78 mg/dL in 30 minutes.\n\n⚠️ Notice: 78 mg/dL is STILL IN-RANGE (70–180 mg/dL)! Ahead alerts you early because of the drop speed, NOT because you are crashing into danger.",
                    aheadRuleOfThumb = "⏳ Rule of 2nd Reading: NEVER panic-eat 40g carbs on the first Yellow ping! 132 is high enough to absorb a drift. Wait for reading #2.",
                    userActionPrompt = "Pause and observe. Do NOT eat extra carbs yet."
                ),
                TutorialStep(
                    readingIndex = 2,
                    timeMinutes = 10,
                    value = 124,
                    ratePerMinute = -1.6,
                    deltaFromPrevious = -8,
                    projected15m = 100,
                    projected30m = 76,
                    severity = "yellow",
                    alertFired = false,
                    stepTitle = "Ping #2 (+10m) — 124 ↘ [Confirming]",
                    coachingHeader = "The 2nd Reading: Braking Begins",
                    coachingBody = "Reading #2 arrives at 124 mg/dL ↘. The drop rate softened slightly from -1.8 to -1.6/min, with projection steady at 76 mg/dL.\n\nBecause you paused instead of panic-eating, you gave your body and CGM lag time to show whether the fall is accelerating or leveling off.",
                    aheadRuleOfThumb = "CGM sensors have a 5–10 minute lag. Reading #2 confirms if a real intervention is needed or if the drop is braking.",
                    userActionPrompt = "Keep watching for Reading #3. If you feel physical low symptoms, verify with a fingerstick."
                ),
                TutorialStep(
                    readingIndex = 3,
                    timeMinutes = 15,
                    value = 119,
                    ratePerMinute = -1.0,
                    deltaFromPrevious = -5,
                    projected15m = 104,
                    projected30m = 89,
                    severity = "none",
                    alertFired = false,
                    stepTitle = "Ping #3 (+15m) — 119 → [Safe Landing!]",
                    coachingHeader = "Safe Landing: 250+ Spike Prevented!",
                    coachingBody = "Reading #3 arrives at 119 mg/dL →. The drop completely flattened out! Severity cleared back to green (none).\n\n🎯 If you had eaten juice + snacks at Ping #1, you would be skyrocketing past 250 mg/dL right now! Waiting 2–3 pings saved you from an unnecessary roller coaster.",
                    aheadRuleOfThumb = "🎯 3 Pings = Clarity. By waiting 2 to 3 pings on yellow, you avoid the dreaded rebound roller coaster!",
                    userActionPrompt = "Success! You stayed safely in-range with zero extra carbs needed."
                )
            )
        ),
        TutorialScenario(
            id = "plunge_red_urgent_action",
            title = "RED Alert: Act Immediately",
            badge = "CRITICAL CONTRAST",
            description = "Contrast with Yellow: When Ahead fires RED under 80 mg/dL, do NOT wait 3 readings — treat immediately!",
            steps = listOf(
                TutorialStep(
                    readingIndex = 0,
                    timeMinutes = 0,
                    value = 84,
                    ratePerMinute = -1.4,
                    deltaFromPrevious = -6,
                    projected15m = 63,
                    projected30m = 42,
                    severity = "yellow",
                    alertFired = true,
                    stepTitle = "Reading #1 (0m) — 84 ↘ [Yellow Caution]",
                    coachingHeader = "Low Guardrail Approaching",
                    coachingBody = "Glucose is 84 mg/dL and falling at -1.4/min, projecting to 63 in 15 minutes. Ahead alerts Yellow to put you on notice that your safety buffer is thin.",
                    aheadRuleOfThumb = "A yellow alert when already under 90 mg/dL requires vigilance. Have carbs within arm's reach.",
                    userActionPrompt = "Get 15g fast carbs (juice, glucose tabs) ready nearby."
                ),
                TutorialStep(
                    readingIndex = 1,
                    timeMinutes = 5,
                    value = 73,
                    ratePerMinute = -2.2,
                    deltaFromPrevious = -11,
                    projected15m = 40,
                    projected30m = 35,
                    severity = "red",
                    alertFired = true,
                    stepTitle = "Reading #2 (+5m) — 73 ⬇ [RED EMERGENCY!]",
                    coachingHeader = "🚨 RED ALERT: Do NOT Wait!",
                    coachingBody = "Ahead fires a RED Alert: 73 mg/dL ⬇ (-11 mg/dL) plunging fast at -2.2/min and projected to 40 mg/dL!\n\n🛑 CRITICAL CONTRAST: This is NOT a yellow alert at 130. You are already near 70 and plunging hard. DO NOT wait for 2nd or 3rd readings! Treat immediately!",
                    aheadRuleOfThumb = "🚨 Red Means Act Now: When Ahead sounds RED and projects below 55, take 15g fast-acting sugar immediately!",
                    userActionPrompt = "Take 15g fast carbs (juice box, 4 glucose tabs) right now!"
                ),
                TutorialStep(
                    readingIndex = 2,
                    timeMinutes = 20,
                    value = 68,
                    ratePerMinute = 0.6,
                    deltaFromPrevious = 3,
                    projected15m = 77,
                    projected30m = 86,
                    severity = "red",
                    alertFired = false,
                    stepTitle = "Reading #3 (+20m) — 68 ↗ [Recovering]",
                    coachingHeader = "Turnaround: Carbs Absorbing",
                    coachingBody = "The carbs hit! Rate flipped positive (+0.6 mg/dL/min). Ahead recognizes the turnaround and switches tone to 'Recovering — not yet stable' so you know your treatment is working without nagging you to eat more.",
                    aheadRuleOfThumb = "Give fast carbs 15 minutes to work. Ahead tracks the rising rate so you don't over-treat during the recovery phase.",
                    userActionPrompt = "Rest and monitor. No need to take more carbs unless symptoms persist."
                )
            )
        ),
        TutorialScenario(
            id = "spike_yellow_prevent_stack",
            title = "Preventing Rage-Boluses (High Spike)",
            badge = "INSULIN LAG",
            description = "Post-meal spike at 196 mg/dL: Learn why rage-bolusing extra insulin causes severe crashes 2 hours later.",
            steps = listOf(
                TutorialStep(
                    readingIndex = 0,
                    timeMinutes = 0,
                    value = 172,
                    ratePerMinute = 1.6,
                    deltaFromPrevious = 8,
                    projected15m = 196,
                    projected30m = 220,
                    severity = "yellow",
                    alertFired = true,
                    stepTitle = "Reading #1 (0m) — 172 ↗ [Yellow High]",
                    coachingHeader = "Post-Meal Spike Alert",
                    coachingBody = "45 minutes after pizza, glucose climbs past 170 mg/dL with a Yellow High alert. Projected to reach 220 in 30 minutes.",
                    aheadRuleOfThumb = "Spikes are frustrating, but rapid insulin takes 60–90 minutes to reach peak activity! Your meal dose is still ramping up.",
                    userActionPrompt = "Check your pump/pen for active insulin on board (IOB) before dosing."
                ),
                TutorialStep(
                    readingIndex = 1,
                    timeMinutes = 10,
                    value = 196,
                    ratePerMinute = 2.4,
                    deltaFromPrevious = 12,
                    projected15m = 232,
                    projected30m = 268,
                    severity = "yellow",
                    alertFired = false,
                    stepTitle = "Reading #2 (+10m) — 196 ↗ [The Rage-Bolus Trap]",
                    coachingHeader = "The Insulin Stacking Trap",
                    coachingBody = "Glucose hits 196 mg/dL. This is when people often 'rage-bolus' 3 extra units in frustration.\n\n⚠️ DANGER: Your meal insulin hasn't peaked yet! Stacking another correction now will hit simultaneously, causing a crash to 45 mg/dL 2 hours later.",
                    aheadRuleOfThumb = "⏳ Don't Stack Insulin: Wait at least 2 to 3 hours after a meal bolus before giving correction insulin.",
                    userActionPrompt = "Drink water or take a brief walk, but hold off on injecting more insulin."
                ),
                TutorialStep(
                    readingIndex = 2,
                    timeMinutes = 25,
                    value = 204,
                    ratePerMinute = 0.8,
                    deltaFromPrevious = 4,
                    projected15m = 216,
                    projected30m = 220,
                    severity = "yellow",
                    alertFired = false,
                    stepTitle = "Reading #3 (+25m) — 204 → [Peak Reached]",
                    coachingHeader = "The Peak Bends: Insulin at Work",
                    coachingBody = "The rise rate slowed down from +2.4 to +0.8/min. The insulin caught the carb wave at 204 mg/dL without stacking. In the next 30 minutes, it will begin smoothly dropping back to normal.",
                    aheadRuleOfThumb = "🎯 Patience Pays Off: Trusting your bolus and avoiding stacking saved you from a severe hypoglycemic crash.",
                    userActionPrompt = "Notice the flat arrow. The insulin is doing its job. No correction needed."
                )
            )
        )
    )
}
