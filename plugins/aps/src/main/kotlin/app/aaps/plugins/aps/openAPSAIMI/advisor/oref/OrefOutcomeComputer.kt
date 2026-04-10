package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import kotlin.math.max
import kotlin.math.min

/**
 * 4-hour forward windows on CGM timeline (same horizon as `predict_user.py`:14400 s).
 * A row is labelled only if at least [minFuturePoints] future readings exist in window.
 */
object OrefOutcomeComputer {

    const val HORIZON_MS: Long = 14_400_000L
    private const val MIN_FUTURE_POINTS = 10

    data class Outcome(
        val minBg4h: Double?,
        val maxBg4h: Double?,
        val hypo4h: Double?,
        val hyper4h: Double?,
    )

    /**
     * @param sortedTsMs ascending CGM timestamps (same series as values)
     * @param sortedMgdl matching glucose values mg/dL
     * @param index index of the prediction row in that series
     */
    fun outcomeAtIndex(sortedTsMs: LongArray, sortedMgdl: DoubleArray, index: Int): Outcome {
        val n = sortedTsMs.size
        if (n < 2 || index < 0 || index >= n) {
            return Outcome(null, null, null, null)
        }
        val t0 = sortedTsMs[index]
        val end = t0 + HORIZON_MS
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        var count = 0
        var j = index + 1
        while (j < n && sortedTsMs[j] <= end) {
            val v = sortedMgdl[j]
            minV = min(minV, v)
            maxV = max(maxV, v)
            count++
            j++
        }
        if (count < MIN_FUTURE_POINTS || !minV.isFinite() || !maxV.isFinite()) {
            return Outcome(null, null, null, null)
        }
        val hypo = if (minV < 70.0) 1.0 else 0.0
        val hyper = if (maxV > 180.0) 1.0 else 0.0
        return Outcome(minV, maxV, hypo, hyper)
    }
}
