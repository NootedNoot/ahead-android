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
            description = "Tonight's exact scenario: Fast drop at 132 mg/dL. See why waiting 2–3 pings avoids a 250+ rebound.",
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
                    userActionPrompt = "No action required. Continue your normal routine."
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
                    stepTitle = "Reading 1 of 3 (10m left) — 132 ↘ [Yellow Alert]",
                    coachingHeader = "The 1st Alert: Yellow Heads-Up",
                    coachingBody = "Ahead sounds a Yellow Alert: 132 mg/dL ↘ (-9 mg/dL). The drop rate jumped to -1.8/min, projecting 78 mg/dL in 30 minutes.\n\n⚠️ Notice: 78 mg/dL is STILL IN-RANGE (70–180 mg/dL)! Ahead alerts you early because of the drop speed, NOT because of immediate low danger.",
                    aheadRuleOfThumb = "⏳ Rule of 2nd Reading: Observe reading #2 before making adjustments. 132 is within target.",
                    userActionPrompt = "Pause and observe. Wait for confirmation before making adjustments."
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
                    stepTitle = "Reading 2 of 3 (5m left) — 124 ↘ [Confirming]",
                    coachingHeader = "The 2nd Reading: Braking Begins",
                    coachingBody = "Reading #2 arrives at 124 mg/dL ↘. The drop rate softened slightly from -1.8 to -1.6/min, with projection steady at 76 mg/dL.\n\nBy observing instead of making unnecessary adjustments, you gave your body and CGM lag time to show whether the fall is accelerating or leveling off.",
                    aheadRuleOfThumb = "CGM sensors have a 5–10 minute lag. Reading #2 confirms if a real intervention is needed or if the drop is braking.",
                    userActionPrompt = "Keep watching for Reading #3. If you feel physical symptoms, verify per your routine."
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
                    stepTitle = "Reading 3 of 3 (Trend Confirmed) — 119 → [Safe Landing!]",
                    coachingHeader = "Safe Landing: Rebound High Prevented!",
                    coachingBody = "Reading #3 arrives at 119 mg/dL →. The drop completely flattened out! Severity cleared back to green (none).\n\n🎯 If unneeded extra carbohydrates had been taken at Ping #1, you could be rebounding past 250 mg/dL right now! Waiting for confirmation saved you from an unnecessary roller coaster.",
                    aheadRuleOfThumb = "🎯 3 Pings = Clarity. By observing 2 to 3 pings on yellow, you avoid the dreaded rebound roller coaster!",
                    userActionPrompt = "Success! You stayed safely in-range without unnecessary adjustments."
                )
            )
        ),
        TutorialScenario(
            id = "plunge_red_urgent_action",
            title = "RED Alert: Act Immediately",
            badge = "CRITICAL CONTRAST",
            description = "Contrast with Yellow: When Ahead fires RED under 80 mg/dL, do NOT wait 3 readings — take immediate action!",
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
                    stepTitle = "Reading 1 of 3 (10m left) — 84 ↘ [Yellow Caution]",
                    coachingHeader = "Low Guardrail Approaching",
                    coachingBody = "Glucose is 84 mg/dL and falling at -1.4/min, projecting to 63 in 15 minutes. Ahead alerts Yellow to put you on notice that your safety buffer is thin.",
                    aheadRuleOfThumb = "A yellow alert when already under 90 mg/dL requires vigilance. Have your routine supplies within arm's reach.",
                    userActionPrompt = "Keep your fast-acting supplies ready nearby."
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
                    stepTitle = "Immediate Red Alert — 73 ⬇ [RED EMERGENCY!]",
                    coachingHeader = "🚨 RED ALERT: Do NOT Wait!",
                    coachingBody = "Ahead fires a RED Alert: 73 mg/dL ⬇ (-11 mg/dL) plunging fast at -2.2/min and projected to 40 mg/dL!\n\n🛑 CRITICAL CONTRAST: This is NOT a yellow alert. Ahead NEVER counts 1 of 3 for Red alerts. Near 70 and plunging hard requires immediate attention per your personal low routine!",
                    aheadRuleOfThumb = "🚨 Red Means Act Now: When Ahead sounds RED and projects low, take immediate action per your routine!",
                    userActionPrompt = "Take immediate action per your routine right now!"
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
                    stepTitle = "Recovery (+20m) — 68 ↗ [Stabilizing]",
                    coachingHeader = "Turnaround: Glucose Stabilizing",
                    coachingBody = "Rate flipped positive (+0.6 mg/dL/min). Ahead recognizes the turnaround and switches tone to 'Recovering — not yet stable' so you know your trend is stabilizing without repeated alarms.",
                    aheadRuleOfThumb = "Ahead tracks the rising rate so you avoid unnecessary adjustments during the recovery phase.",
                    userActionPrompt = "Rest and monitor. Continue to observe your routine."
                )
            )
        ),
        TutorialScenario(
            id = "spike_yellow_prevent_stack",
            title = "Preventing Stacking (High Spike)",
            badge = "INSULIN LAG",
            description = "Post-meal spike at 196 mg/dL: Learn why stacking extra adjustments too soon causes severe crashes later.",
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
                    stepTitle = "Reading 1 of 3 (10m left) — 172 ↗ [Yellow High]",
                    coachingHeader = "Post-Meal Spike Alert",
                    coachingBody = "45 minutes after pizza, glucose climbs past 170 mg/dL with a Yellow High alert. Projected to reach 220 in 30 minutes.",
                    aheadRuleOfThumb = "Spikes are frustrating, but rapid insulin takes 60–90 minutes to reach peak activity! Your meal dose is still ramping up.",
                    userActionPrompt = "Check your pump/pen for active insulin on board (IOB) before making adjustments."
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
                    stepTitle = "Reading 2 of 3 (5m left) — 196 ↗ [The Stacking Trap]",
                    coachingHeader = "The Insulin Stacking Trap",
                    coachingBody = "Glucose hits 196 mg/dL. This is when people often make hasty extra adjustments in frustration.\n\n⚠️ CAUTION: Your meal insulin hasn't peaked yet! Stacking another adjustment now will hit simultaneously, causing a crash 2 hours later.",
                    aheadRuleOfThumb = "⏳ Avoid Stacking: Allow adequate time after a meal before making additional adjustments.",
                    userActionPrompt = "Drink water or take a brief walk, but hold off on stacking additional adjustments."
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
                    stepTitle = "Reading 3 of 3 (Trend Confirmed) — 204 → [Peak Reached]",
                    coachingHeader = "The Peak Bends: Insulin at Work",
                    coachingBody = "The rise rate slowed down from +2.4 to +0.8/min. The insulin caught the carb wave at 204 mg/dL without stacking. In the next 30 minutes, it will begin smoothly dropping back into range.",
                    aheadRuleOfThumb = "🎯 Patience Pays Off: Trusting your initial plan and avoiding stacking saved you from a severe hypoglycemic crash.",
                    userActionPrompt = "Notice the flat arrow. The curve is leveling off. No extra adjustment needed."
                )
            )
        )
    )
}
