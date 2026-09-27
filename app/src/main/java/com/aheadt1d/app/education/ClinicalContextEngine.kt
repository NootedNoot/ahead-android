package com.aheadt1d.app.education

import com.aheadt1d.app.events.UserEvent
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.notifications.GlucoseDisplayState
import kotlin.math.abs

/**
 * Contextual Clinical Insights Engine for Ahead.
 *
 * Connects the user's real-time metabolic and behavioral data to peer-reviewed
 * educational resources at the exact moment they care:
 *
 * 1. Sick Days & Ketones -> DKA cascade, stress hormones, GLUT4 blocking (#dka)
 * 2. Hypoglycemia Unawareness & Lows -> HAAF, autonomic reset, loss of glucagon (#hypo)
 * 3. Insulin Stacking & Rapid Climbs -> Subcutaneous absorption delay, stacking trap (#cellular)
 * 4. Sensor Dynamics & Steep Trends -> Interstitial fluid diffusion delay (5-15 min) (#cgm)
 * 5. Sustained Highs -> Endothelial glycocalyx degradation & microvascular stress (#complications)
 * 6. In-Range Cellular Balance -> Normal GLUT4 translocation & mitochondrial ATP (#cellular)
 *
 * Purely educational - explains underlying human physiology without prescribing
 * individualized medical dosages or treatment instructions.
 */
data class ClinicalInsight(
    val id: String,
    val badge: String,
    val title: String,
    val snippet: String,
    val targetUrl: String,
    val actionText: String = "Read in Resources ▶",
    val severity: String = "normal", // "normal", "yellow", "red"
    val shortBadge: String = badge,
    val shortTitle: String = "Why this matters: $title"
)

object ClinicalContextEngine {

    const val BASE_RESOURCES_URL = "https://aheadt1d.com/resources.html"

    private val SICK_KEYWORDS = listOf(
        "sick", "fever", "illness", "cold", "flu", "covid", "virus",
        "ketone", "ketones", "nausea", "vomit", "stomach"
    )

    /**
     * Pure functional evaluator that ranks physiological relevance.
     */
    fun evaluate(
        reading: GlucoseDisplayState.Reading?,
        recentReadings: List<GlucosePoint> = emptyList(),
        recentEvents: List<UserEvent> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): ClinicalInsight {
        val sgv = reading?.value ?: 100
        val rate = reading?.ratePerMinute ?: 0.0
        val recoveringFromLow = reading?.recoveringFromLow ?: false
        val projected15m = reading?.projected ?: sgv

        // 1. SICK DAY / ILLNESS (Priority 1: Critical Acute Safety Window)
        // Logged illness event or note mentioning sick/fever/ketones within last 48 hours
        val sickWindowMs = 48 * 60 * 60_000L
        val recentSickEvent = recentEvents.firstOrNull { event ->
            (nowMillis - event.timestamp) in 0L..sickWindowMs && (
                event.tag.equals("illness", ignoreCase = true) ||
                SICK_KEYWORDS.any { kw -> event.note?.contains(kw, ignoreCase = true) == true }
            )
        }
        if (recentSickEvent != null) {
            val isHigh = sgv >= 240
            return ClinicalInsight(
                id = "sick_day_ketones",
                badge = "🤒 SICK DAY PHYSIOLOGY",
                title = "Stress Hormones & The Ketone Cascade",
                snippet = "Illness unleashes counter-regulatory cortisol and epinephrine, blocking GLUT4 transporters and driving sudden insulin resistance. In T1D, cells can starve and produce dangerous ketones even at near-normal glucose levels.",
                targetUrl = "$BASE_RESOURCES_URL#dka",
                actionText = "Read Sick Day Protocol ▶",
                severity = if (isHigh) "red" else "yellow",
                shortBadge = "🤒",
                shortTitle = "Why This Matters: Ketone Cascade"
            )
        }

        // 2. HYPOGLYCEMIA UNAWARENESS & RECURRENT LOWS (Priority 2)
        // Current low, recovering from low, projected low, or frequent lows in history (>= 3 in last 7 days)
        val sevenDaysMs = 7 * 24 * 60 * 60_000L
        val recentLowsCount = recentReadings.count {
            (nowMillis - it.time.toEpochMilli()) in 0L..sevenDaysMs && it.sgv < 70
        }
        val isCurrentLow = sgv <= 70
        val isProjectedLow = projected15m <= 65
        val hasFrequentLows = recentLowsCount >= 3

        if (isCurrentLow || recoveringFromLow || isProjectedLow || hasFrequentLows) {
            val title = if (hasFrequentLows && !isCurrentLow) {
                "Hypoglycemia Unawareness & Adrenaline Reset"
            } else {
                "Hypoglycemia & The Brain's Energy Crisis"
            }
            val snippet = if (hasFrequentLows) {
                "When lows happen frequently in a week, the brain's autonomic alarm thermostat resets downward (HAAF). Shaking and adrenaline sweats stop firing at 70 mg/dL. In T1D, pancreatic alpha cells permanently lose glucagon sensing, leaving you with zero automatic brakes."
            } else {
                "The brain consumes 20% of resting glucose but stores zero glycogen. Dropping glucose triggers an immediate sympathoadrenal adrenaline blast. Because T1D disables alpha-cell glucagon release, external carbs or rescue glucagon are essential to halt the fall."
            }
            return ClinicalInsight(
                id = "hypo_unawareness",
                badge = "⚡ AUTONOMIC RESET",
                title = title,
                snippet = snippet,
                targetUrl = "$BASE_RESOURCES_URL#hypo",
                actionText = "Why This Matters: Lows & HAAF ▶",
                severity = if (isCurrentLow) "red" else "yellow",
                shortBadge = "⚡",
                shortTitle = if (hasFrequentLows) "Why This Matters: Lost Warning Signs" else "Why This Matters: Hypo & The Brain"
            )
        }

        // 3. INSULIN KINETICS & THE STACKING TRAP (Priority 3)
        // Rapid rise (>= +1.5/min) while elevated, or recent correction in last 90 min while still climbing
        val ninetyMinMs = 90 * 60_000L
        val recentCorrection = recentEvents.firstOrNull { event ->
            (nowMillis - event.timestamp) in 0L..ninetyMinMs &&
            (event.tag.equals("correction", ignoreCase = true) || event.tag.equals("insulin", ignoreCase = true))
        }
        val isRapidRise = rate >= 1.5 && sgv >= 150
        val isStackingRisk = recentCorrection != null && (rate > 0.5 || sgv >= 180)

        if (isRapidRise || isStackingRisk) {
            return ClinicalInsight(
                id = "insulin_stacking",
                badge = "💉 INSULIN KINETICS",
                title = "The Stacking Trap & Subcutaneous Absorption Lag",
                snippet = "Rapid-acting analog insulin takes 60–90 minutes to reach peak cellular absorption and up to 4–5 hours to clear. Correcting an early rise before peak activity stacks insulin doses on top of each other, setting up severe delayed crashes.",
                targetUrl = "$BASE_RESOURCES_URL#cellular",
                actionText = "Why This Matters: Insulin Stacking ▶",
                severity = "yellow",
                shortBadge = "💉",
                shortTitle = "Why This Matters: Insulin Stacking"
            )
        }

        // 4. SENSOR DYNAMICS & INTERSTITIAL DIFFUSION LAG (Priority 4)
        // High rate of change (|rate| >= 1.5 mg/dL/min)
        if (abs(rate) >= 1.5) {
            return ClinicalInsight(
                id = "sensor_lag",
                badge = "📡 SENSOR DYNAMICS",
                title = "Interstitial Fluid Delay (5–15 Min Lag)",
                snippet = "CGM sensors sample interstitial fluid surrounding fat cells, not blood. During rapid climbs or steep plunges, physiological glucose diffusion creates a 5 to 15-minute lag. A capillary fingerstick will lead a falling CGM and lag a rising one.",
                targetUrl = "$BASE_RESOURCES_URL#cgm",
                actionText = "Why This Matters: Sensor Lag ▶",
                severity = "yellow",
                shortBadge = "📡",
                shortTitle = "Why This Matters: Sensor Lag (5–15m)"
            )
        }

        // 5. SUSTAINED HIGHS & VASCULAR STRESS (Priority 5)
        // High glucose (>= 200 mg/dL)
        if (sgv >= 200) {
            return ClinicalInsight(
                id = "vascular_stress",
                badge = "🔬 VASCULAR HEALTH",
                title = "Endothelial Glycocalyx & Microvascular Stress",
                snippet = "Extended exposure above 180–200 mg/dL causes shear degradation of the endothelial glycocalyx—the delicate gel-like lining of renal and retinal microvessels. Keeping time-in-range tight preserves capillary barrier integrity.",
                targetUrl = "$BASE_RESOURCES_URL#complications",
                actionText = "Why This Matters: Microvascular Health ▶",
                severity = "yellow",
                shortBadge = "🔬",
                shortTitle = "Why This Matters: Microvascular Health"
            )
        }

        // 6. TARGET BASELINE & CELLULAR ENERGY BALANCE (Default)
        // In-range stability
        return ClinicalInsight(
            id = "cellular_balance",
            badge = "🟢 CELLULAR BALANCE",
            title = "Cellular Energy Balance & GLUT4 Translocation",
            snippet = "In target range, insulin receptors activate GLUT4 transporters smoothly, allowing glucose to enter skeletal muscle and fuel mitochondrial ATP synthesis without triggering counter-regulatory stress or microvascular shear.",
            targetUrl = "$BASE_RESOURCES_URL#cellular",
            actionText = "Explore Biology of T1D ▶",
            severity = "normal",
            shortBadge = "💡",
            shortTitle = "Why This Matters: Cellular Energy"
        )
    }
}
