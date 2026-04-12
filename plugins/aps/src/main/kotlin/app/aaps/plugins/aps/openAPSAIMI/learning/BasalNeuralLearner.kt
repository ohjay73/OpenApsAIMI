package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import android.os.Environment
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.io.File
import java.util.Locale

/**
 * BasalNeuralLearner identifies user-specific glycemic response patterns
 * to optimize basal adjustments.
 *
 * It supports two modes:
 * 1. T3C Brittle Mode (Aggressive corrective PI basal)
 * 2. Universal Adaptive Basal (Subtle scaling of standard TBRs for all users)
 */
@Singleton
class BasalNeuralLearner @Inject constructor(
    private val context: Context,
    private val preferences: Preferences,
    private val storageHelper: app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper,
    private val log: AAPSLogger
) {
    enum class GovernanceAction {
        WARMUP,
        KEEP,
        HOLD_CONSERVATIVE,
        ALLOW_GENTLE_INCREASE
    }

    data class GovernanceSnapshot(
        val action: GovernanceAction,
        val confidence: Double,
        val sampleCount: Int,
        /** Fraction of samples with BG below hypo threshold (unweighted count / n). */
        val hypoRate: Double,
        /** Hypo fraction used for HOLD enter/exit (noise- and context-weighted). */
        val hypoRateGovernance: Double = hypoRate,
        /** Same as [hypoRateGovernance] after short-horizon prediction relief (A3). */
        val hypoGovernanceAdjusted: Double = hypoRateGovernance,
        /** 0 = no predictive relief; 1 = strong relief from recent min-pred vs hypo band. */
        val anticipationRelief: Double = 0.0,
        val severeHypoCount: Int,
        val highRate: Double,
        val meanAbsTargetError: Double,
        val reason: String,
        val timestamp: Long,
        /** True when [action] is [GovernanceAction.HOLD_CONSERVATIVE] only because of exit hysteresis (raw metrics already improved). */
        val hypoHoldLatched: Boolean = false,
        /** Effective basal scaling floor applied this evaluation when in HOLD; null otherwise. */
        val activeBasalFloor: Double? = null,
        /** Effective T3C aggressiveness floor when in HOLD; null otherwise. */
        val activeAggressivenessFloor: Double? = null,
        /** Mean per-sample governance weight (sensor noise + IOB/drop context); 1.0 = legacy unweighted behaviour. */
        val meanGovernanceWeight: Double = 1.0,
    )

    private data class LearningSample(
        val timestamp: Long,
        val bgBefore: Double,
        val bgAfter: Double,
        val targetBg: Double,
        /** BG change (mg/dL per typical loop interval) used for context scoring. */
        val deltaMgDl: Double,
        val iobUnits: Double,
        /** CGM noise (0 = clean; higher = less trust in this sample for governance). */
        val sensorNoise: Double,
        /** Min BG across short prediction curves at this tick (mg/dL); null if unavailable. */
        val shortMinPredBg: Double? = null,
    )

    private var internalAggressivenessFactor = 1.0 // Heuristic fallback for T3C
    private var internalBasalScalingFactor = 1.0    // Heuristic fallback for Universal
    
    private var neuralT3cNet: AimiNeuralNetwork? = null
    private var neuralBasalNet: AimiNeuralNetwork? = null

    private val governanceWindow = ArrayDeque<LearningSample>(GOVERNANCE_WINDOW_MAX + 1)
    private var lastGovernanceSnapshot = GovernanceSnapshot(
        action = GovernanceAction.WARMUP,
        confidence = 0.0,
        sampleCount = 0,
        hypoRate = 0.0,
        severeHypoCount = 0,
        highRate = 0.0,
        meanAbsTargetError = 0.0,
        reason = "Warmup",
        timestamp = System.currentTimeMillis(),
        hypoHoldLatched = false,
        activeBasalFloor = null,
        activeAggressivenessFloor = null,
        meanGovernanceWeight = 1.0,
    )
    private var lastGovernanceLogAt = 0L
    
    init {
        loadModels()
    }

    private fun loadModels() {
        val t3cWeights = storageHelper.getAimiFile("t3c_brain_weights.json")
        val basalWeights = storageHelper.getAimiFile("basal_adaptive_weights.json")

        if (t3cWeights.exists()) {
            neuralT3cNet = AimiNeuralNetwork.loadFromFile(t3cWeights)
        }
        if (basalWeights.exists()) {
            neuralBasalNet = AimiNeuralNetwork.loadFromFile(basalWeights)
        }
    }

    /**
     * Returns the aggressiveness factor for T3C Brittle Mode.
     */
    fun getT3cAdaptiveFactor(
        bg: Double,
        basal: Double,
        accel: Double,
        duraMin: Double,
        duraAvg: Double,
        iob: Double
    ): Double {
        val baseAggressiveness = preferences.get(DoubleKey.OApsAIMIT3cAggressiveness)
        val neuralFactor = neuralT3cNet?.predict(floatArrayOf(bg.toFloat(), basal.toFloat(), accel.toFloat(), duraMin.toFloat(), duraAvg.toFloat(), iob.toFloat()))?.get(0)
        
        return baseAggressiveness * (neuralFactor ?: internalAggressivenessFactor)
    }

    /**
     * Returns the scaling factor for Universal Adaptive Basal.
     */
    fun getUniversalBasalMultiplier(
        bg: Double,
        basal: Double,
        accel: Double,
        duraMin: Double,
        duraAvg: Double,
        iob: Double
    ): Double {
        if (!preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)) return 1.0
        val maxScaling = preferences.get(DoubleKey.OApsAIMIAdaptiveBasalMaxScaling)
        
        val neuralFactor = neuralBasalNet?.predict(floatArrayOf(bg.toFloat(), basal.toFloat(), accel.toFloat(), duraMin.toFloat(), duraAvg.toFloat(), iob.toFloat()))?.get(0)
        
        return (neuralFactor ?: internalBasalScalingFactor).coerceIn(0.7, max(1.0, maxScaling))
    }

    /**
     * Updates the internal state and LOGS data for Neural Training.
     */
    fun updateLearning(
        bgBefore: Double,
        bgAfter: Double,
        basalDelivered: Double,
        targetBg: Double,
        accel: Double,
        duraISFminutes: Double,
        duraISFaverage: Double,
        iob: Double,
        loopDeltaMgDl5m: Double? = null,
        sensorNoise: Double = 0.0,
        shortMinPredBg: Double? = null,
    ) {
        val isT3cActive = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)
        val isAdaptiveBasalActive = preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)
        val delta = loopDeltaMgDl5m?.takeIf { it.isFinite() } ?: (bgAfter - bgBefore)
        
        // 1. T3C Heuristic (Aggressive)
        if (isT3cActive) {
            if (bgBefore > 180.0 && delta >= 0) {
                internalAggressivenessFactor = min(internalAggressivenessFactor + 0.05, 3.0)
            }
            if (delta < -15.0) {
                internalAggressivenessFactor = max(internalAggressivenessFactor - 0.1, 0.5)
            }
        }

        // 2. Universal Adaptive Basal Heuristic (Subtle)
        if (isAdaptiveBasalActive) {
            // If BG is high (>140) and not dropping fast enough
            if (bgBefore > 140.0 && delta > -2.0) {
                internalBasalScalingFactor = min(internalBasalScalingFactor + 0.01, 3.0)
            }
            // If BG is low (<90) or dropping too fast
            if (bgBefore < 90.0 || delta < -8.0) {
                internalBasalScalingFactor = max(internalBasalScalingFactor - 0.02, 0.8)
            }
        }

        // 3. Data Logging (Synchronous for reliability in loop)
        try {
            logRecord(bgBefore, bgAfter, basalDelivered, targetBg, accel, duraISFminutes, duraISFaverage, iob)
            updateGovernanceWindow(
                bgBefore = bgBefore,
                bgAfter = bgAfter,
                targetBg = targetBg,
                deltaMgDl = delta,
                iobUnits = iob,
                sensorNoise = sensorNoise,
                shortMinPredBg = shortMinPredBg,
            )
            evaluateGovernance()
        } catch (e: Exception) {
            log.error("LEARNER_LOG", "Failed to log records: ${e.message}")
        }
    }

    @Synchronized
    fun getGovernanceSnapshot(): GovernanceSnapshot = lastGovernanceSnapshot

    private fun logRecord(
        bg: Double,
        eventualBg: Double,
        basal: Double,
        target: Double,
        accel: Double,
        duraMin: Double,
        duraAvg: Double,
        iob: Double
    ) {
        val file = storageHelper.getAimiFile("basal_adaptive_records.csv")
        
        if (!file.exists()) {
            file.writeText("timestamp,bg,eventualBg,basal,target,accel,duraMin,duraAvg,iob,t3cAgg,basalScale\n")
        }
        
        val row = "${System.currentTimeMillis()},$bg,$eventualBg,$basal,$target,$accel,$duraMin,$duraAvg,$iob,$internalAggressivenessFactor,$internalBasalScalingFactor\n"
        file.appendText(row)
    }

    private fun updateGovernanceWindow(
        bgBefore: Double,
        bgAfter: Double,
        targetBg: Double,
        deltaMgDl: Double,
        iobUnits: Double,
        sensorNoise: Double,
        shortMinPredBg: Double?,
    ) {
        if (!bgBefore.isFinite() || !bgAfter.isFinite() || !targetBg.isFinite()) return
        val noise = if (sensorNoise.isFinite()) sensorNoise else 0.0
        val iob = if (iobUnits.isFinite()) iobUnits else 0.0
        val d = if (deltaMgDl.isFinite()) deltaMgDl else (bgAfter - bgBefore)
        val predMin = shortMinPredBg?.takeIf { it.isFinite() }
        governanceWindow.addFirst(
            LearningSample(
                timestamp = System.currentTimeMillis(),
                bgBefore = bgBefore,
                bgAfter = bgAfter,
                targetBg = targetBg,
                deltaMgDl = d,
                iobUnits = iob,
                sensorNoise = noise,
                shortMinPredBg = predMin,
            )
        )
        while (governanceWindow.size > GOVERNANCE_WINDOW_MAX) governanceWindow.removeLast()
    }

    /**
     * Short-horizon prediction relief: among recent samples that carry a min-pred value,
     * fraction where predicted trough sits above the hypo band (anticipates recovery).
     */
    private fun governanceAnticipationRelief(
        samples: List<LearningSample>,
        hypoBgThreshold: Double,
        lookback: Int,
        marginMgdl: Double,
    ): Double {
        if (samples.isEmpty()) return 0.0
        val n = minOf(lookback.coerceAtLeast(1), samples.size)
        val recent = samples.take(n)
        val withPred = recent.filter { it.shortMinPredBg != null }
        if (withPred.isEmpty()) return 0.0
        val margin = hypoBgThreshold + marginMgdl
        val hits = withPred.count { (it.shortMinPredBg ?: 0.0) >= margin }
        return (hits.toDouble() / withPred.size.toDouble()).coerceIn(0.0, 1.0)
    }

    /** Pulls multiplicative decay closer to 1.0 when [blend] > 0 (less aggressive rollback). */
    private fun blendDecayTowardNeutral(decay: Double, blend: Double): Double {
        val d = decay.coerceIn(0.90, 0.999)
        val b = blend.coerceIn(0.0, 1.0)
        return 1.0 - (1.0 - d) * (1.0 - b)
    }

    /** Per-sample weight for governance (on-device): down-rank noisy CGM and very fast drops with little IOB. */
    private fun governanceSampleWeight(s: LearningSample): Double {
        val noiseTrust = when {
            !s.sensorNoise.isFinite() || s.sensorNoise <= 0.0 -> 1.0
            s.sensorNoise >= 3.0 -> GOVERNANCE_WEIGHT_NOISE_TIER3
            s.sensorNoise >= 2.0 -> GOVERNANCE_WEIGHT_NOISE_TIER2
            s.sensorNoise >= 1.0 -> GOVERNANCE_WEIGHT_NOISE_TIER1
            else -> (1.0 - s.sensorNoise * GOVERNANCE_WEIGHT_NOISE_LINEAR).coerceIn(GOVERNANCE_WEIGHT_MIN, 1.0)
        }
        val rapidFall = s.deltaMgDl < GOVERNANCE_RAPID_FALL_DELTA_MGDL
        val lowIob = s.iobUnits < GOVERNANCE_LOW_IOB_THRESHOLD_U
        val iobFactor = if (rapidFall && lowIob) GOVERNANCE_WEIGHT_RAPID_FALL_LOW_IOB else 1.0
        return (noiseTrust * iobFactor).coerceIn(GOVERNANCE_WEIGHT_MIN, 1.0)
    }

    private data class EffectiveGovernanceParams(
        val hypoBgThreshold: Double,
        val severeBgThreshold: Double,
        val hypoRateEnter: Double,
        val hypoRateExit: Double,
        val holdBasalFloorRate: Double,
        val holdBasalDecayRate: Double,
        val holdAggFloorRate: Double,
        val holdAggDecayRate: Double,
        val holdBasalFloorSevere: Double,
        val holdBasalDecaySevere: Double,
        val holdAggFloorSevere: Double,
        val holdAggDecaySevere: Double,
        val anticipationLookback: Int,
        val anticipationMarginMgdl: Double,
        val anticipationHypoDamp: Double,
        val anticipationDecayBlendMax: Double,
    )

    /**
     * Reads user preferences and applies safe cross-constraints (exit rate below enter rate, severe BG below hypo BG, severe tier at least as tight as rate tier).
     */
    private fun effectiveGovernanceParams(): EffectiveGovernanceParams {
        val hypoBg = preferences.get(DoubleKey.OApsAIMIGovernanceHypoBgMgdl)
            .coerceIn(DoubleKey.OApsAIMIGovernanceHypoBgMgdl.min, DoubleKey.OApsAIMIGovernanceHypoBgMgdl.max)
        val severeBgRaw = preferences.get(DoubleKey.OApsAIMIGovernanceSevereHypoBgMgdl)
            .coerceIn(DoubleKey.OApsAIMIGovernanceSevereHypoBgMgdl.min, DoubleKey.OApsAIMIGovernanceSevereHypoBgMgdl.max)
        val severeBg = min(severeBgRaw, hypoBg - 1.0).coerceAtLeast(54.0)

        var hypoRateEnter = preferences.get(DoubleKey.OApsAIMIGovernanceHypoRateEnter)
            .coerceIn(DoubleKey.OApsAIMIGovernanceHypoRateEnter.min, DoubleKey.OApsAIMIGovernanceHypoRateEnter.max)
        var hypoRateExit = preferences.get(DoubleKey.OApsAIMIGovernanceHypoRateExit)
            .coerceIn(DoubleKey.OApsAIMIGovernanceHypoRateExit.min, DoubleKey.OApsAIMIGovernanceHypoRateExit.max)
        if (hypoRateExit >= hypoRateEnter) {
            hypoRateExit = (hypoRateEnter - 0.01).coerceAtLeast(DoubleKey.OApsAIMIGovernanceHypoRateExit.min)
        }

        fun clampedDecay(key: DoubleKey): Double =
            preferences.get(key).coerceIn(key.min, key.max).coerceAtMost(0.999).coerceAtLeast(0.90)

        fun clampedFloor(key: DoubleKey): Double =
            preferences.get(key).coerceIn(key.min, key.max)

        var holdBasalFloorRate = clampedFloor(DoubleKey.OApsAIMIGovernanceHoldBasalFloorRate)
        var holdBasalDecayRate = clampedDecay(DoubleKey.OApsAIMIGovernanceHoldBasalDecayRate)
        var holdAggFloorRate = clampedFloor(DoubleKey.OApsAIMIGovernanceHoldAggFloorRate)
        var holdAggDecayRate = clampedDecay(DoubleKey.OApsAIMIGovernanceHoldAggDecayRate)
        var holdBasalFloorSevere = clampedFloor(DoubleKey.OApsAIMIGovernanceHoldBasalFloorSevere)
        var holdBasalDecaySevere = clampedDecay(DoubleKey.OApsAIMIGovernanceHoldBasalDecaySevere)
        var holdAggFloorSevere = clampedFloor(DoubleKey.OApsAIMIGovernanceHoldAggFloorSevere)
        var holdAggDecaySevere = clampedDecay(DoubleKey.OApsAIMIGovernanceHoldAggDecaySevere)

        if (holdBasalFloorSevere < holdBasalFloorRate) holdBasalFloorSevere = holdBasalFloorRate
        holdBasalDecaySevere = min(holdBasalDecaySevere, holdBasalDecayRate)
        if (holdAggFloorSevere < holdAggFloorRate) holdAggFloorSevere = holdAggFloorRate
        holdAggDecaySevere = min(holdAggDecaySevere, holdAggDecayRate)

        val anticipationLookback = preferences.get(DoubleKey.OApsAIMIGovernanceAnticipationLookbackSamples)
            .coerceIn(
                DoubleKey.OApsAIMIGovernanceAnticipationLookbackSamples.min,
                DoubleKey.OApsAIMIGovernanceAnticipationLookbackSamples.max,
            )
            .roundToInt()
            .coerceIn(1, 288)
        val anticipationMarginMgdl = preferences.get(DoubleKey.OApsAIMIGovernanceAnticipationMarginMgdl)
            .coerceIn(
                DoubleKey.OApsAIMIGovernanceAnticipationMarginMgdl.min,
                DoubleKey.OApsAIMIGovernanceAnticipationMarginMgdl.max,
            )
        val anticipationHypoDamp = preferences.get(DoubleKey.OApsAIMIGovernanceAnticipationHypoDamp)
            .coerceIn(
                DoubleKey.OApsAIMIGovernanceAnticipationHypoDamp.min,
                DoubleKey.OApsAIMIGovernanceAnticipationHypoDamp.max,
            )
        val anticipationDecayBlendMax = preferences.get(DoubleKey.OApsAIMIGovernanceAnticipationDecayBlendMax)
            .coerceIn(
                DoubleKey.OApsAIMIGovernanceAnticipationDecayBlendMax.min,
                DoubleKey.OApsAIMIGovernanceAnticipationDecayBlendMax.max,
            )

        return EffectiveGovernanceParams(
            hypoBgThreshold = hypoBg,
            severeBgThreshold = severeBg,
            hypoRateEnter = hypoRateEnter,
            hypoRateExit = hypoRateExit,
            holdBasalFloorRate = holdBasalFloorRate,
            holdBasalDecayRate = holdBasalDecayRate,
            holdAggFloorRate = holdAggFloorRate,
            holdAggDecayRate = holdAggDecayRate,
            holdBasalFloorSevere = holdBasalFloorSevere,
            holdBasalDecaySevere = holdBasalDecaySevere,
            holdAggFloorSevere = holdAggFloorSevere,
            holdAggDecaySevere = holdAggDecaySevere,
            anticipationLookback = anticipationLookback,
            anticipationMarginMgdl = anticipationMarginMgdl,
            anticipationHypoDamp = anticipationHypoDamp,
            anticipationDecayBlendMax = anticipationDecayBlendMax,
        )
    }

    internal fun evaluateGovernance() {
        val now = System.currentTimeMillis()
        if (governanceWindow.isEmpty()) return

        val p = effectiveGovernanceParams()
        val samples = governanceWindow.toList()
        val count = samples.size
        val hypoCount = samples.count { it.bgAfter < p.hypoBgThreshold }
        val highCount = samples.count { it.bgAfter > 180.0 }
        val meanAbsTargetError = samples.map { abs(it.bgAfter - it.targetBg) }.average()

        val hypoRateUnweighted = hypoCount.toDouble() / count.toDouble()
        var weightSum = 0.0
        var hypoWeightedSum = 0.0
        samples.forEach { s ->
            val w = governanceSampleWeight(s)
            weightSum += w
            if (s.bgAfter < p.hypoBgThreshold) hypoWeightedSum += w
        }
        val hypoRateGovernance = if (weightSum > 0.0) hypoWeightedSum / weightSum else hypoRateUnweighted
        val meanGovernanceWeight = if (count > 0) weightSum / count.toDouble() else 1.0
        val severeHypoCount = samples.count {
            it.bgAfter < p.severeBgThreshold && governanceSampleWeight(it) >= GOVERNANCE_SEVERE_MIN_SAMPLE_WEIGHT
        }
        val highRate = highCount.toDouble() / count.toDouble()
        val confidence = (count.toDouble() / GOVERNANCE_WINDOW_MAX.toDouble()).coerceIn(0.0, 1.0)

        val anticipationRelief = governanceAnticipationRelief(
            samples,
            p.hypoBgThreshold,
            p.anticipationLookback,
            p.anticipationMarginMgdl,
        )
        val hypoGovernanceAdjusted =
            (hypoRateGovernance * (1.0 - anticipationRelief * p.anticipationHypoDamp))
                .coerceIn(0.0, 1.0)

        val previousAction = lastGovernanceSnapshot.action
        val rawHypoPressure = severeHypoCount >= 1 || hypoGovernanceAdjusted >= p.hypoRateEnter
        val hypoPressureClearForExit = severeHypoCount == 0 && hypoGovernanceAdjusted < p.hypoRateExit

        val action: GovernanceAction
        val reason: String
        val hypoHoldLatched: Boolean
        if (count < GOVERNANCE_MIN_SAMPLES) {
            action = GovernanceAction.WARMUP
            reason = "Not enough samples"
            hypoHoldLatched = false
        } else if (rawHypoPressure) {
            action = GovernanceAction.HOLD_CONSERVATIVE
            reason = "Hypo pressure detected"
            hypoHoldLatched = false
        } else if (previousAction == GovernanceAction.HOLD_CONSERVATIVE && !hypoPressureClearForExit) {
            // Exit hysteresis: avoid flicker when hypoRate oscillates around the enter threshold.
            action = GovernanceAction.HOLD_CONSERVATIVE
            reason = "Hypo pressure latched (hysteresis)"
            hypoHoldLatched = true
        } else if (highRate >= 0.45 && hypoRateUnweighted <= 0.05 && meanAbsTargetError >= 35.0) {
            action = GovernanceAction.ALLOW_GENTLE_INCREASE
            reason = "Persistent hyper pattern"
            hypoHoldLatched = false
        } else {
            action = GovernanceAction.KEEP
            reason = "Balanced risk profile"
            hypoHoldLatched = false
        }

        val severeTier = severeHypoCount >= 1
        val basalFloor = if (action == GovernanceAction.HOLD_CONSERVATIVE) {
            if (severeTier) p.holdBasalFloorSevere else p.holdBasalFloorRate
        } else {
            null
        }
        val aggFloor = if (action == GovernanceAction.HOLD_CONSERVATIVE) {
            if (severeTier) p.holdAggFloorSevere else p.holdAggFloorRate
        } else {
            null
        }

        lastGovernanceSnapshot = GovernanceSnapshot(
            action = action,
            confidence = confidence,
            sampleCount = count,
            hypoRate = hypoRateUnweighted,
            hypoRateGovernance = hypoRateGovernance,
            hypoGovernanceAdjusted = hypoGovernanceAdjusted,
            anticipationRelief = anticipationRelief,
            severeHypoCount = severeHypoCount,
            highRate = highRate,
            meanAbsTargetError = meanAbsTargetError,
            reason = reason,
            timestamp = now,
            hypoHoldLatched = hypoHoldLatched,
            activeBasalFloor = basalFloor,
            activeAggressivenessFloor = aggFloor,
            meanGovernanceWeight = meanGovernanceWeight,
        )

        if (action != GovernanceAction.WARMUP) {
            applyGovernanceGuardrails(action, severeTier, p, anticipationRelief)
        }
        maybeLogGovernance(lastGovernanceSnapshot)
    }

    private fun applyGovernanceGuardrails(
        action: GovernanceAction,
        severeHypoTier: Boolean,
        p: EffectiveGovernanceParams,
        anticipationRelief: Double,
    ) {
        when (action) {
            GovernanceAction.HOLD_CONSERVATIVE -> {
                // Soft rollback toward neutral to avoid over-learning during hypo-prone windows.
                val basalFloor = if (severeHypoTier) p.holdBasalFloorSevere else p.holdBasalFloorRate
                val basalDecayRaw = if (severeHypoTier) p.holdBasalDecaySevere else p.holdBasalDecayRate
                val aggFloor = if (severeHypoTier) p.holdAggFloorSevere else p.holdAggFloorRate
                val aggDecayRaw = if (severeHypoTier) p.holdAggDecaySevere else p.holdAggDecayRate
                val decayBlend =
                    anticipationRelief.coerceIn(0.0, 1.0) * p.anticipationDecayBlendMax.coerceIn(0.0, 1.0)
                val basalDecay = blendDecayTowardNeutral(basalDecayRaw, decayBlend)
                val aggDecay = blendDecayTowardNeutral(aggDecayRaw, decayBlend)
                internalBasalScalingFactor = max(basalFloor, internalBasalScalingFactor * basalDecay)
                internalAggressivenessFactor = max(aggFloor, internalAggressivenessFactor * aggDecay)
            }
            GovernanceAction.ALLOW_GENTLE_INCREASE -> {
                // Keep adaptation gentle even in strong hyper windows.
                internalBasalScalingFactor = min(internalBasalScalingFactor, 1.35)
                internalAggressivenessFactor = min(internalAggressivenessFactor, 2.20)
            }
            GovernanceAction.KEEP, GovernanceAction.WARMUP -> {
                // No guardrail override.
            }
        }
    }

    private fun maybeLogGovernance(snapshot: GovernanceSnapshot) {
        val now = System.currentTimeMillis()
        val hasActionChanged = snapshot.action != lastLoggedAction
        if (!hasActionChanged && (now - lastGovernanceLogAt) < GOVERNANCE_LOG_MIN_MS) return

        lastGovernanceLogAt = now
        lastLoggedAction = snapshot.action

        log.info(
            LTag.APS,
            String.format(
                Locale.US,
                "action=%s conf=%.2f n=%d hypo=%.2f hypoG=%.2f hypoAdj=%.2f ant=%.2f wMean=%.2f severe=%d high=%.2f mae=%.1f latch=%s floorB=%s floorA=%s reason=%s",
                snapshot.action.name,
                snapshot.confidence,
                snapshot.sampleCount,
                snapshot.hypoRate,
                snapshot.hypoRateGovernance,
                snapshot.hypoGovernanceAdjusted,
                snapshot.anticipationRelief,
                snapshot.meanGovernanceWeight,
                snapshot.severeHypoCount,
                snapshot.highRate,
                snapshot.meanAbsTargetError,
                snapshot.hypoHoldLatched,
                snapshot.activeBasalFloor?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
                snapshot.activeAggressivenessFloor?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
                snapshot.reason
            )
        )
    }

    private var lastLoggedAction: GovernanceAction = GovernanceAction.WARMUP

    /**
     * Replaces the governance window (for unit tests).
     * [bgAfters] is chronological (oldest → newest); internal deque matches production (newest at front).
     */
    internal fun replaceGovernanceSamplesForTesting(
        bgAfters: List<Double>,
        targetBg: Double = 100.0,
        sensorNoises: List<Double>? = null,
        iobUnits: List<Double>? = null,
        deltasMgDl: List<Double>? = null,
        shortMinPredBgs: List<Double>? = null,
    ) {
        governanceWindow.clear()
        val slice = bgAfters.takeLast(GOVERNANCE_WINDOW_MAX)
        val offset = bgAfters.size - slice.size
        // addFirst oldest→newest so front = newest (matches updateGovernanceWindow / take(n) = recent)
        for (i in slice.indices) {
            val bg = slice[i]
            val idx = offset + i
            val noise = sensorNoises?.getOrNull(idx)?.takeIf { it.isFinite() } ?: 0.0
            val iob = iobUnits?.getOrNull(idx)?.takeIf { it.isFinite() } ?: 0.0
            val d = deltasMgDl?.getOrNull(idx)?.takeIf { it.isFinite() } ?: 0.0
            val pred = shortMinPredBgs?.getOrNull(idx)?.takeIf { it.isFinite() }
            governanceWindow.addFirst(
                LearningSample(
                    timestamp = idx.toLong(),
                    bgBefore = bg,
                    bgAfter = bg,
                    targetBg = targetBg,
                    deltaMgDl = d,
                    iobUnits = iob,
                    sensorNoise = noise,
                    shortMinPredBg = pred,
                )
            )
        }
    }

    /**
     * Resets governance + heuristic factors (for unit tests).
     */
    internal fun resetGovernanceStateForTesting() {
        governanceWindow.clear()
        lastGovernanceSnapshot = GovernanceSnapshot(
            action = GovernanceAction.WARMUP,
            confidence = 0.0,
            sampleCount = 0,
            hypoRate = 0.0,
            severeHypoCount = 0,
            highRate = 0.0,
            meanAbsTargetError = 0.0,
            reason = "Warmup",
            timestamp = System.currentTimeMillis(),
            hypoHoldLatched = false,
            activeBasalFloor = null,
            activeAggressivenessFloor = null,
            meanGovernanceWeight = 1.0,
        )
        internalBasalScalingFactor = 1.0
        internalAggressivenessFactor = 1.0
        lastLoggedAction = GovernanceAction.WARMUP
        lastGovernanceLogAt = 0L
    }

    private companion object {
        const val GOVERNANCE_WINDOW_MAX = 288 // ~24h @ 5-min cadence
        const val GOVERNANCE_MIN_SAMPLES = 36 // ~3h warmup
        const val GOVERNANCE_LOG_MIN_MS = 5 * 60 * 1000L

        const val GOVERNANCE_WEIGHT_NOISE_TIER3 = 0.35
        const val GOVERNANCE_WEIGHT_NOISE_TIER2 = 0.55
        const val GOVERNANCE_WEIGHT_NOISE_TIER1 = 0.75
        const val GOVERNANCE_WEIGHT_NOISE_LINEAR = 0.15
        const val GOVERNANCE_WEIGHT_MIN = 0.25
        const val GOVERNANCE_RAPID_FALL_DELTA_MGDL = -12.0
        const val GOVERNANCE_LOW_IOB_THRESHOLD_U = 0.35
        const val GOVERNANCE_WEIGHT_RAPID_FALL_LOW_IOB = 0.75
        const val GOVERNANCE_SEVERE_MIN_SAMPLE_WEIGHT = 0.45
    }
}
