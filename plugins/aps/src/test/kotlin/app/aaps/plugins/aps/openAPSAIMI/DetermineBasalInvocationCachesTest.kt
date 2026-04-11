package app.aaps.plugins.aps.openAPSAIMI

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.TDD
import app.aaps.core.interfaces.stats.TddCalculator
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class DetermineBasalInvocationCachesTest {

    @Test
    fun `two getTdd24h in same invocation hits calculator once`() {
        val caches = DetermineBasalInvocationCaches()
        val calls = AtomicInteger(0)
        val tdd = mockk<TddCalculator>(relaxed = true)
        coEvery { tdd.calculateDaily(-24, 0) } coAnswers {
            calls.incrementAndGet()
            TDD(timestamp = 1L, totalAmount = 40.0)
        }
        caches.beginInvocation()
        val a = caches.getTdd24hTotalAmountCached(tdd)
        val b = caches.getTdd24hTotalAmountCached(tdd)
        assertThat(a).isEqualTo(40.0)
        assertThat(b).isEqualTo(40.0)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `beginInvocation invalidates cache for next round`() {
        val caches = DetermineBasalInvocationCaches()
        val calls = AtomicInteger(0)
        val tdd = mockk<TddCalculator>(relaxed = true)
        coEvery { tdd.calculateDaily(-24, 0) } coAnswers {
            calls.incrementAndGet()
            TDD(timestamp = 1L, totalAmount = calls.get().toDouble())
        }
        caches.beginInvocation()
        assertThat(caches.getTdd24hTotalAmountCached(tdd)).isEqualTo(1.0)
        caches.beginInvocation()
        assertThat(caches.getTdd24hTotalAmountCached(tdd)).isEqualTo(2.0)
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `getTdd1d sparse cached within invocation`() {
        val caches = DetermineBasalInvocationCaches()
        val calls = AtomicInteger(0)
        val tdd = mockk<TddCalculator>(relaxed = true)
        val sparse = LongSparseArray<TDD>().apply {
            put(1L, TDD(timestamp = 1L, totalAmount = 33.0))
        }
        coEvery { tdd.calculate(1L, false) } coAnswers {
            calls.incrementAndGet()
            sparse
        }
        caches.beginInvocation()
        assertThat(caches.getTddCalculate1DaySparseCached(tdd)).isSameInstanceAs(sparse)
        assertThat(caches.getTddCalculate1DaySparseCached(tdd)).isSameInstanceAs(sparse)
        assertThat(calls.get()).isEqualTo(1)
    }
}
