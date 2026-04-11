package app.aaps.plugins.aps.openAPSAIMI

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.TDD
import app.aaps.core.interfaces.stats.TIR
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import kotlinx.coroutines.runBlocking

/**
 * Per-[determine_basal] invocation caches for suspend stats calls ([runBlocking]).
 *
 * **TDD 24h** — [TddCalculator.calculateDaily]`(-24, 0)` is read from several places in one
 * `determine_basal` pass. Caching avoids redundant DB work.
 *
 * **TDD 1d** — [TddCalculator.calculate]`(1, allowMissingDays = false)` mid-pipeline and again for safety.
 *
 * **TIR** — [TirCalculator.calculate]`(1, 65, 180)` after warmup block; [storeTir65180FromWarmup] fills the cache
 * from the existing warmup [runBlocking] to avoid a second identical suspend call.
 *
 * Call [beginInvocation] once at the start of each [determine_basal] pass. Thread-safe.
 *
 * **Not covered here** (still `runBlocking` in `DetermineBasalaimiSMB2`): basal history init;
 * boluses in finalize path; open-agent boluses; site changes; newest SMB bolus;
 * warmup carbs / future COB / recent notes; recent bolus count; bolusesHistory;
 * [TddCalculator.calculate]`(2, false)`; HealthConnect steps/HR; other [TirCalculator] ranges
 * (daily/hour/3d).
 */
internal class DetermineBasalInvocationCaches {

    private val lock = Any()
    private var invocationSeq: Long = 0L

    private var cachedTdd24hSeq: Long = -1L
    private var cachedTdd24hTotalAmount: Double? = null

    private var cachedTdd1DaySparseSeq: Long = -1L
    private var cachedTdd1DaySparse: LongSparseArray<TDD>? = null

    private var cachedTir65180Seq: Long = -1L
    private var cachedTir65180: LongSparseArray<TIR>? = null

    fun beginInvocation() {
        synchronized(lock) {
            invocationSeq++
        }
    }

    fun getTdd24hTotalAmountCached(tddCalculator: TddCalculator): Double? {
        synchronized(lock) {
            if (cachedTdd24hSeq == invocationSeq) {
                return cachedTdd24hTotalAmount
            }
            val result = runBlocking { tddCalculator.calculateDaily(-24, 0) }
            val total = result?.totalAmount
            cachedTdd24hTotalAmount = total
            cachedTdd24hSeq = invocationSeq
            return total
        }
    }

    fun getTddCalculate1DaySparseCached(tddCalculator: TddCalculator): LongSparseArray<TDD>? {
        synchronized(lock) {
            if (cachedTdd1DaySparseSeq == invocationSeq) {
                return cachedTdd1DaySparse
            }
            val r = runBlocking { tddCalculator.calculate(1, allowMissingDays = false) }
            cachedTdd1DaySparse = r
            cachedTdd1DaySparseSeq = invocationSeq
            return r
        }
    }

    fun getTirCalculate1Day65180Cached(tirCalculator: TirCalculator): LongSparseArray<TIR> {
        synchronized(lock) {
            if (cachedTir65180Seq == invocationSeq && cachedTir65180 != null) {
                return cachedTir65180!!
            }
        }
        val r = runBlocking { tirCalculator.calculate(1, 65.0, 180.0) }
        synchronized(lock) {
            cachedTir65180 = r
            cachedTir65180Seq = invocationSeq
        }
        return r
    }

    fun storeTir65180FromWarmup(result: LongSparseArray<TIR>) {
        synchronized(lock) {
            cachedTir65180 = result
            cachedTir65180Seq = invocationSeq
        }
    }
}
