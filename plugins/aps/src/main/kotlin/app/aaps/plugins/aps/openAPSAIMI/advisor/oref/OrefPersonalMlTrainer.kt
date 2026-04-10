package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import android.content.Context
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import java.io.File
import kotlin.math.exp
import kotlin.random.Random

/**
 * Trains small on-device MLPs on OREF feature rows (same 35 inputs as ONNX) for hypo/hyper 4h labels.
 * Weights stored under [filesDir]/oref_personal/ — no Python, no cloud.
 */
object OrefPersonalMlTrainer {

    /** Per head (hypo and hyper); ~10 days of active looping often reaches this. */
    const val MIN_LABELLED_SAMPLES = 120
    private const val HIDDEN = 16

    private fun dir(ctx: Context): File = File(ctx.filesDir, "oref_personal").apply { mkdirs() }

    fun hypoFile(ctx: Context): File = File(dir(ctx), "personal_hypo_mlp.json")
    fun hyperFile(ctx: Context): File = File(dir(ctx), "personal_hyper_mlp.json")

    data class PersonalMlOutcome(
        val status: OrefPersonalMlStatus,
        val meanHypoSignalPct: Double? = null,
        val meanHyperSignalPct: Double? = null,
        val detail: String? = null,
    )

    fun trainAndSummarize(
        ctx: Context,
        slices: List<Triple<Int, DoubleArray, Long>>,
        outcomePerSlice: List<OrefOutcomeComputer.Outcome>,
    ): PersonalMlOutcome {
        val hypoPairs = ArrayList<Pair<FloatArray, Double>>()
        val hyperPairs = ArrayList<Pair<FloatArray, Double>>()
        for (i in slices.indices) {
            val x = toFloatInput(slices[i].second)
            val o = outcomePerSlice[i]
            o.hypo4h?.let { y -> hypoPairs += x to y }
            o.hyper4h?.let { y -> hyperPairs += x to y }
        }
        if (hypoPairs.size < MIN_LABELLED_SAMPLES || hyperPairs.size < MIN_LABELLED_SAMPLES) {
            return PersonalMlOutcome(
                OrefPersonalMlStatus.INSUFFICIENT_DATA,
                detail = "labelled_hypo=${hypoPairs.size} labelled_hyper=${hyperPairs.size} (need >=$MIN_LABELLED_SAMPLES each)",
            )
        }
        return try {
            val hypoNet = trainOneHead(hypoPairs, Random(42L))
            hypoNet.saveToFile(hypoFile(ctx))
            val hyperNet = trainOneHead(hyperPairs, Random(43L))
            hyperNet.saveToFile(hyperFile(ctx))

            val meanH = meanSigmoid(hypoNet, slices)
            val meanHy = meanSigmoid(hyperNet, slices)
            PersonalMlOutcome(OrefPersonalMlStatus.TRAINED_AND_USED, meanH * 100.0, meanHy * 100.0, null)
        } catch (t: Throwable) {
            PersonalMlOutcome(OrefPersonalMlStatus.TRAIN_FAILED, detail = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun trainOneHead(pairs: List<Pair<FloatArray, Double>>, rng: Random): AimiNeuralNetwork {
        val cfg = TrainingConfig(
            learningRate = 0.002,
            epochs = 48,
            patience = 6,
            batchSize = 64,
            useBatchNorm = false,
            useDropout = false,
            weightDecay = 0.005,
        )
        val net = AimiNeuralNetwork(OrefModelFeatures.COUNT, HIDDEN, 1, cfg, regularizationLambda = 0.005)
        val idx = pairs.indices.shuffled(rng)
        val split = (idx.size * 0.85).toInt().coerceAtLeast(1)
        val trIdx = idx.take(split)
        val vaIdx = idx.drop(split).ifEmpty { trIdx.takeLast(1) }
        val trainInputs = trIdx.map { pairs[it].first }
        val trainTargets = trIdx.map { doubleArrayOf(pairs[it].second) }
        val valInputs = vaIdx.map { pairs[it].first }
        val valTargets = vaIdx.map { doubleArrayOf(pairs[it].second) }
        net.trainWithValidation(trainInputs, trainTargets, valInputs, valTargets)
        return net
    }

    private fun meanSigmoid(net: AimiNeuralNetwork, slices: List<Triple<Int, DoubleArray, Long>>): Double {
        if (slices.isEmpty()) return 0.0
        var s = 0.0
        for ((_, f, _) in slices) {
            val p = sigmoid(net.predict(toFloatInput(f))[0])
            s += p
        }
        return s / slices.size
    }

    fun toFloatInput(row: DoubleArray): FloatArray {
        val n = OrefModelFeatures.COUNT
        return FloatArray(n) { j ->
            val v = row.getOrNull(j) ?: Double.NaN
            if (v.isFinite()) v.toFloat() else 0f
        }
    }

    private fun sigmoid(raw: Double): Double {
        val x = raw.coerceIn(-30.0, 30.0)
        return 1.0 / (1.0 + exp(-x))
    }
}
