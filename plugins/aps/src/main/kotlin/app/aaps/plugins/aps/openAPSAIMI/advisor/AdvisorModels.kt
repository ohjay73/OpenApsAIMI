package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.plugins.aps.R

/**
 * =============================================================================
 * ADVISOR MODELS
 * =============================================================================
 */

// --- ENUMS ---
enum class AdvisorSeverity { GOOD, WARNING, CRITICAL }
enum class RecommendationPriority { LOW, MEDIUM, HIGH, CRITICAL }
enum class RecommendationDomain { BASAL, ISF, MAX_SMB, TARGET, SAFETY, MODES, PROFILE_QUALITY, SMB }

enum class AdvisorActionCode {
    INCREASE_NIGHT_BASAL,
    DECREASE_NIGHT_BASAL,
    INCREASE_ISF,
    DECREASE_ISF,
    REDUCE_MAX_SMB,
    INCREASE_LUNCH_FACTOR,
    ENABLE_UAM
    // Add more as needed
}

// --- DATA CLASSES ---

data class AimiProfileSnapshot(
    val nightBasal: Double,
    val icRatio: Double,
    val isf: Double,
    val targetBg: Double
)

data class AimiPrefsSnapshot(
    val maxSmb: Double,
    val lunchFactor: Double,
    val unifiedReactivityFactor: Double,
    val autodriveMaxBasal: Double
)

data class AdvisorMetrics(
    val periodLabel: String,
    val tir70_180: Double,
    val tir70_140: Double,
    val timeBelow70: Double,
    val timeBelow54: Double,
    val timeAbove180: Double,
    val timeAbove250: Double,
    val meanBg: Double,
    val gmi: Double,
    val tdd: Double,
    val basalPercent: Double,
    val hypoEvents: Int,
    val severeHypoEvents: Int,
    val hyperEvents: Int
)

data class AdvisorContext(
    val metrics: AdvisorMetrics,
    val profile: AimiProfileSnapshot,
    val prefs: AimiPrefsSnapshot
)

data class AdvisorAction(
    val actionCode: AdvisorActionCode,
    val params: Map<String, Any> = emptyMap()
)

data class AimiRecommendation(
    val domain: RecommendationDomain,
    val priority: RecommendationPriority,
    val titleResId: Int,
    val descriptionResId: Int,
    val actionsResIds: List<Int> = emptyList(),
    val advisorActions: List<AdvisorAction> = emptyList()
)

data class AdvisorReport(
    val generatedAt: Long,
    val metrics: AdvisorMetrics,
    val overallScore: Double,
    val overallSeverity: AdvisorSeverity,
    val overallAssessment: String,
    val recommendations: List<AimiRecommendation>,
    val summary: String // Legacy/Debug summary
)
