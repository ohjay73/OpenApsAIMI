package app.aaps.plugins.aps.openAPSAIMI.smb

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.math.exp

/**
 * Locks the descent re-dose guard on the three reference episodes it was measured on, plus one case
 * per condition.
 *
 * The reference episodes carry their real numbers. They are the reason the guard exists, and the
 * reason condition (f) was added:
 *  - the night of 08/09, three boluses re-dosing a descent, which ended at BG 70.4;
 *  - the lunch of 09/09, a good burst of 8.96 U that the guard must leave alone;
 *  - the afternoon of 11/09, thirteen blocks in a row while BG was going up: 13 false positives.
 */
class DescentRedoseGuardTest {

    private val t0 = 1_757_000_000_000L // arbitrary epoch anchor, the guard only uses differences

    private fun at(minutes: Double) = t0 + (minutes * 60_000L).toLong()

    /** Builds readings from (minute offset, mg/dL) pairs. */
    private fun series(vararg points: Pair<Double, Double>) =
        points.map { DescentRedoseGuard.Reading(at(it.first), it.second) }

    // ---------------------------------------------------------------------------------------
    // Reference episode 1 — night of 08/09. Peak 232.5, three re-doses at +57, +63 and +98 min.
    // ---------------------------------------------------------------------------------------

    /**
     * The measured trace, on the 5 min CGM cadence, with the peak at offset 0.
     *
     * It holds the local bump the guard was built for: 170.2 at +40 then 186.8 at +55, so BG is
     * *rising* at the moment of the first re-dose.
     */
    private val night0809 = series(
        0.0 to 232.5,
        5.0 to 226.0,
        10.0 to 218.0,
        15.0 to 209.0,
        20.0 to 200.0,
        25.0 to 192.0,
        30.0 to 185.0,
        35.0 to 177.0,
        40.0 to 170.2, // trough of the first two re-doses
        45.0 to 174.0,
        50.0 to 180.0,
        55.0 to 186.8, // first re-dose reads this
        60.0 to 190.8, // second re-dose reads this
        65.0 to 188.0,
        70.0 to 183.0,
        75.0 to 178.0,
        80.0 to 173.0,
        85.0 to 169.0,
        90.0 to 166.6, // trough of the third re-dose
        95.0 to 171.6, // third re-dose reads this
    )

    /**
     * OPEN QUESTION, do not "fix" this test silently.
     *
     * Condition (f) removes the three blocks of the 08/09 night: at each of those ticks the fitted
     * slope is positive (+1.28, +1.08 and +0.26 mg/dL/min), because each re-dose landed on a bump
     * inside the descent. That is exactly the shape condition (f) was asked to refuse, and it is
     * also exactly the shape the guard was originally built to catch, so the two goals collide.
     *
     * The slope of the 08/09 bumps (+0.26 to +1.28) sits inside the range of the 13 false positives
     * of 11/09 (+0.24 to +1.36), so the slope alone does not separate the two episodes. Changing
     * [DescentRedoseGuard.MAX_SLOPE_MGDL_PER_MIN] is a decision to be taken with the measured data,
     * not a detail of this test.
     */
    @Test
    fun `night of 08-09 no longer blocks because BG was going up at each re-dose`() {
        val first = DescentRedoseGuard.evaluate(night0809, nowMs = at(57.0), iobU = 13.81)
        val second = DescentRedoseGuard.evaluate(night0809, nowMs = at(63.0), iobU = 14.29)
        val third = DescentRedoseGuard.evaluate(night0809, nowMs = at(98.0), iobU = 11.28)

        for (verdict in listOf(first, second, third)) {
            assertThat(verdict.block).isFalse()
            assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_BG_RISING)
            assertThat(verdict.slopeMgdlPerMin!!).isGreaterThan(0.0)
        }
        assertThat(first.slopeMgdlPerMin!!).isWithin(0.01).of(1.28)
        assertThat(second.slopeMgdlPerMin!!).isWithin(0.01).of(1.08)
        assertThat(third.slopeMgdlPerMin!!).isWithin(0.01).of(0.26)
    }

    /** Only condition (f) changed the 08/09 verdict: (a) to (e) still all hold at those ticks. */
    @Test
    fun `night of 08-09 still satisfies conditions a to e`() {
        val first = DescentRedoseGuard.evaluate(
            night0809, nowMs = at(57.0), iobU = 13.81, maxSlopeMgdlPerMin = Double.MAX_VALUE,
        )
        assertThat(first.block).isTrue()
        assertThat(first.peakMgdl).isWithin(1e-9).of(232.5)
        assertThat(first.peakAgeMinutes).isWithin(1e-9).of(57.0)
        assertThat(first.currentBgMgdl).isWithin(1e-9).of(186.8)
        assertThat(first.troughMgdl).isWithin(1e-9).of(170.2)

        val second = DescentRedoseGuard.evaluate(
            night0809, nowMs = at(63.0), iobU = 14.29, maxSlopeMgdlPerMin = Double.MAX_VALUE,
        )
        assertThat(second.block).isTrue()
        // The bump is +20.6 over the trough, still inside the basin, so condition (e) holds.
        assertThat(second.reboundFromTroughMgdl).isWithin(1e-9).of(20.6)

        val third = DescentRedoseGuard.evaluate(
            night0809, nowMs = at(98.0), iobU = 11.28, maxSlopeMgdlPerMin = Double.MAX_VALUE,
        )
        assertThat(third.block).isTrue()
        assertThat(third.troughMgdl).isWithin(1e-9).of(166.6)
        assertThat(third.peakAgeMinutes).isWithin(1e-9).of(98.0)
    }

    // ---------------------------------------------------------------------------------------
    // Reference episode 2 — lunch of 09/09, a good burst of 8.96 U that must not be touched.
    // ---------------------------------------------------------------------------------------

    /** BG 128 up to a peak of 240.4, then a plateau ending at 240.2, a fall of only 0.2 mg/dL. */
    private val lunch0909 = series(
        0.0 to 128.0,
        5.0 to 136.0,
        10.0 to 149.0,
        15.0 to 165.0,
        20.0 to 183.0,
        25.0 to 201.0,
        30.0 to 218.0,
        35.0 to 231.0,
        40.0 to 240.4, // peak
        45.0 to 239.5,
        50.0 to 238.9,
        55.0 to 239.2,
        60.0 to 239.8,
        65.0 to 240.0,
        70.0 to 240.1,
        75.0 to 240.2,
    )

    @Test
    fun `lunch of 09-09 is not blocked because BG only fell 0 point 2 from the peak`() {
        val verdict = DescentRedoseGuard.evaluate(lunch0909, nowMs = at(77.0), iobU = 9.0)

        assertThat(verdict.block).isFalse()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_DROP_TOO_SMALL)
        assertThat(verdict.peakMgdl).isWithin(1e-9).of(240.4)
        assertThat(verdict.currentBgMgdl).isWithin(1e-9).of(240.2)
        // The peak is old enough and IOB is heavy, so (a) (b) (d) all hold; only (c) refuses.
        assertThat(verdict.peakAgeMinutes!!).isAtLeast(DescentRedoseGuard.PEAK_AGE_MINUTES)
        assertThat(verdict.dropFromPeakMgdl).isWithin(1e-9).of(0.2)
    }

    @Test
    fun `no moment of the 09-09 rise is blocked whatever the IOB`() {
        for (index in lunch0909.indices) {
            val now = lunch0909[index].timeMs + 120_000L // two minutes after that reading
            for (iob in listOf(0.0, 6.0, 9.0, 14.0)) {
                val verdict = DescentRedoseGuard.evaluate(
                    lunch0909.subList(0, index + 1), nowMs = now, iobU = iob,
                )
                assertThat(verdict.block).isFalse()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reference episode 3 — afternoon of 11/09, thirteen blocks in a row on a BG that was rising.
    // ---------------------------------------------------------------------------------------

    /**
     * The measured rise of 11/09, from 15:49 to 16:02, about one reading per minute.
     *
     * Offsets are minutes after the peak of 14:33 (191.2 mg/dL), so 15:49 sits at +76 min, which is
     * the peak age the export recorded for that tick. There is no 15:54 reading, as in the export.
     */
    private val rise1109 = listOf(
        76.0 to 139.5, 77.0 to 141.4, 78.0 to 141.5, 79.0 to 142.8, 80.0 to 142.8,
        82.0 to 148.8, 83.0 to 151.3, 84.0 to 153.3, 85.0 to 153.2, 86.0 to 153.2,
        87.0 to 155.5, 88.0 to 157.3, 89.0 to 159.6,
    )

    /** IOB at each of the 13 ticks, in the same order, U. */
    private val iob1109 = listOf(
        6.21, 6.66, 6.57, 6.50, 6.42, 6.77, 6.88, 6.90, 6.92, 6.94, 6.96, 6.99, 7.01,
    )

    /**
     * The whole afternoon: the fall down to the lowest point, then the measured rise.
     *
     * Only the rise was exported. The fall from 191.2 at 14:33 to 136.0 at 15:47 is modelled as a
     * slowing fall, which is what a CGM trace does when it bottoms out. The verdicts it produces
     * match the export closely: at 15:49 it gives drop 51.7 for a measured 52.2, and rebound 3.5
     * for a measured 2.7.
     */
    private val afternoon1109 = buildList {
        for (minute in 0..73) {
            add(DescentRedoseGuard.Reading(at(minute.toDouble()), 133.0 + 58.2 * exp(-minute / 28.0)))
        }
        add(DescentRedoseGuard.Reading(at(74.0), 136.0)) // lowest point, 15:47
        add(DescentRedoseGuard.Reading(at(75.0), 137.6))
        addAll(rise1109.map { DescentRedoseGuard.Reading(at(it.first), it.second) })
    }

    private fun verdict1109(index: Int, maxSlope: Double = DescentRedoseGuard.MAX_SLOPE_MGDL_PER_MIN) =
        DescentRedoseGuard.evaluate(
            afternoon1109,
            nowMs = at(rise1109[index].first),
            iobU = iob1109[index],
            maxSlopeMgdlPerMin = maxSlope,
        )

    /** Without condition (f) the shipped guard blocks all thirteen ticks. This is the bug. */
    @Test
    fun `without condition f the 13 ticks of 11-09 all block`() {
        for (index in rise1109.indices) {
            assertThat(verdict1109(index, maxSlope = Double.MAX_VALUE).block).isTrue()
        }
    }

    @Test
    fun `the 11-09 ticks are no longer blocked once the fitted slope is positive`() {
        val stillBlocked = mutableListOf<String>()
        for (index in 2..rise1109.indices.last) {
            val verdict = verdict1109(index)
            if (verdict.block) {
                stillBlocked += String.format(
                    Locale.US, "+%.0f min %s", rise1109[index].first, verdict.reason,
                )
            } else {
                assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_BG_RISING)
                assertThat(verdict.slopeMgdlPerMin!!).isGreaterThan(0.0)
            }
        }
        assertThat(stillBlocked).isEmpty()
    }

    /**
     * KNOWN RESIDUAL, on purpose: the first two ticks after the lowest point still block.
     *
     * This is not a data artefact, it is what a 15 min least-squares fit has to say. At 15:49 the
     * window reaches back to 15:34, when BG was still on its way down, and BG at 15:34 can only be
     * higher than at 15:49 because 15:47 was the lowest point. So the fit is still negative there
     * whatever the shape of the fall, and no threshold on a 15 min slope can free that tick.
     *
     * 11 of the 13 false positives are removed. Freeing the last two needs a different measure (a
     * shorter window, or a rise counted from the lowest point), which is a separate decision.
     */
    @Test
    fun `the first two ticks of 11-09 still block because the slope window still holds the fall`() {
        for (index in 0..1) {
            val verdict = verdict1109(index)
            assertThat(verdict.block).isTrue()
            assertThat(verdict.slopeMgdlPerMin!!).isLessThan(0.0)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Condition (e) — the rebound guard is required, not a refinement.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a new rise after the peak is not blocked even though a b c and d all hold`() {
        val newRise = series(
            0.0 to 250.0, // peak
            10.0 to 225.0,
            20.0 to 200.0,
            30.0 to 175.0,
            40.0 to 158.0,
            50.0 to 150.0, // trough
            60.0 to 162.0,
            70.0 to 178.0,
            80.0 to 192.0,
            90.0 to 200.0, // 50 mg/dL above the trough: this is a new rise, not a bump
        )
        val verdict = DescentRedoseGuard.evaluate(newRise, nowMs = at(92.0), iobU = 8.0)

        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_REBOUND_ABOVE_TROUGH)
        assertThat(verdict.block).isFalse()
        // Proof that only (e) saved it: (a) (b) (c) (d) are all satisfied here.
        assertThat(verdict.peakMgdl!!).isAtLeast(DescentRedoseGuard.PEAK_MIN_MGDL)
        assertThat(verdict.peakAgeMinutes!!).isAtLeast(DescentRedoseGuard.PEAK_AGE_MINUTES)
        assertThat(verdict.dropFromPeakMgdl!!).isAtLeast(DescentRedoseGuard.DROP_MGDL)
        assertThat(verdict.reboundFromTroughMgdl!!).isGreaterThan(DescentRedoseGuard.REBOUND_MGDL)
    }

    // ---------------------------------------------------------------------------------------
    // Condition (f) — the slope guard.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a flat trace has a slope of exactly zero and still blocks`() {
        val flat = series(
            0.0 to 250.0, // peak
            10.0 to 200.0,
            19.0 to 150.0,
            24.0 to 150.0,
            29.0 to 150.0,
            34.0 to 150.0,
        )
        val verdict = DescentRedoseGuard.evaluate(flat, nowMs = at(34.0), iobU = 10.0)

        assertThat(verdict.slopeMgdlPerMin).isWithin(1e-12).of(0.0)
        assertThat(verdict.block).isTrue()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_BLOCKED)
    }

    @Test
    fun `fewer than three readings in the slope window never blocks`() {
        // 8 min cadence: the last 15 min hold only two readings, so the slope cannot be trusted.
        val sparse = series(
            0.0 to 232.5, // peak
            8.0 to 215.0,
            16.0 to 200.0,
            24.0 to 185.0,
            32.0 to 175.0,
            40.0 to 170.0,
        )
        val verdict = DescentRedoseGuard.evaluate(sparse, nowMs = at(42.0), iobU = 10.0)

        assertThat(verdict.block).isFalse()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_SLOPE_NO_DATA)
        assertThat(verdict.slopeMgdlPerMin).isNull()
        // Everything else held: without (f) this tick would have blocked.
        val withoutF = DescentRedoseGuard.evaluate(
            sparse, nowMs = at(42.0), iobU = 10.0, maxSlopeMgdlPerMin = Double.MAX_VALUE,
        )
        assertThat(withoutF.reasonCode).isEqualTo(DescentRedoseGuard.REASON_SLOPE_NO_DATA)
    }

    @Test
    fun `the slope never reaches back across a hole longer than ten minutes`() {
        val withHole = series(
            0.0 to 250.0, // steep fall before the hole
            10.0 to 225.0,
            20.0 to 200.0,
            // 28 min hole here
            48.0 to 140.0,
            50.0 to 142.0,
            52.0 to 144.0,
            54.0 to 146.0,
        )
        val verdict = DescentRedoseGuard.evaluate(withHole, nowMs = at(55.0), iobU = 12.0)

        // Only the four readings after the hole are used, and they are going up at 1 mg/dL/min.
        assertThat(verdict.slopeMgdlPerMin!!).isWithin(1e-9).of(1.0)
        assertThat(verdict.block).isFalse()
    }

    // ---------------------------------------------------------------------------------------
    // Data holes.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a hole longer than ten minutes hides the peak that sits before it`() {
        val withHole = series(
            0.0 to 250.0, // peak, but on the far side of the hole
            5.0 to 240.0,
            10.0 to 230.0,
            // 30 min hole here
            40.0 to 150.0,
            45.0 to 148.0,
            50.0 to 146.0,
            55.0 to 145.0,
        )
        val verdict = DescentRedoseGuard.evaluate(withHole, nowMs = at(57.0), iobU = 12.0)

        assertThat(verdict.block).isFalse()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_PEAK_TOO_LOW)
        // The peak seen is the highest value AFTER the hole, never the 250 before it.
        assertThat(verdict.peakMgdl).isWithin(1e-9).of(150.0)
    }

    @Test
    fun `the same series without the hole does see the peak and blocks`() {
        val noHole = series(
            0.0 to 250.0,
            5.0 to 240.0,
            10.0 to 230.0,
            18.0 to 210.0,
            26.0 to 190.0,
            34.0 to 170.0,
            40.0 to 150.0,
            45.0 to 148.0,
            50.0 to 146.0,
            55.0 to 145.0,
        )
        val verdict = DescentRedoseGuard.evaluate(noHole, nowMs = at(57.0), iobU = 12.0)

        assertThat(verdict.block).isTrue()
        assertThat(verdict.peakMgdl).isWithin(1e-9).of(250.0)
        assertThat(verdict.slopeMgdlPerMin!!).isLessThan(0.0)
    }

    @Test
    fun `an empty window says no data and never blocks`() {
        val verdict = DescentRedoseGuard.evaluate(emptyList(), nowMs = at(10.0), iobU = 20.0)

        assertThat(verdict.block).isFalse()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_NO_DATA)
        assertThat(verdict.peakMgdl).isNull()
        assertThat(verdict.slopeMgdlPerMin).isNull()
    }

    @Test
    fun `a window whose newest reading is stale never blocks`() {
        val verdict = DescentRedoseGuard.evaluate(night0809, nowMs = at(115.0), iobU = 13.0)

        assertThat(verdict.block).isFalse()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_STALE_DATA)
    }

    // ---------------------------------------------------------------------------------------
    // One case per condition: everything else holds, only the named condition is missing.
    // ---------------------------------------------------------------------------------------

    /** A descent that blocks, scaled so each condition can be broken one at a time. */
    private fun descent(
        peak: Double = 232.5,
        trough: Double = 170.0,
        current: Double = 180.0,
    ) = series(
        0.0 to peak,
        10.0 to (peak + trough) / 2.0,
        20.0 to trough + 5.0,
        30.0 to trough,
        40.0 to current,
    )

    /** A descent that is still falling at the end, so all six conditions hold. */
    private val fallingDescent = series(
        0.0 to 232.5, // peak
        10.0 to 200.0,
        20.0 to 180.0,
        28.0 to 174.0,
        34.0 to 171.0,
        40.0 to 170.0, // trough, and current value
    )

    @Test
    fun `the reference descent blocks when all six conditions hold`() {
        val verdict = DescentRedoseGuard.evaluate(fallingDescent, nowMs = at(42.0), iobU = 10.0)

        assertThat(verdict.block).isTrue()
        assertThat(verdict.slopeMgdlPerMin!!).isLessThan(0.0)
    }

    @Test
    fun `condition a alone missing - the peak never reached 180`() {
        val verdict = DescentRedoseGuard.evaluate(
            descent(peak = 175.0, trough = 120.0, current = 130.0), nowMs = at(42.0), iobU = 10.0,
        )

        assertThat(verdict.block).isFalse()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_PEAK_TOO_LOW)
    }

    @Test
    fun `condition b alone missing - the peak is only 20 minutes behind`() {
        val quick = series(
            0.0 to 232.5,
            5.0 to 210.0,
            10.0 to 195.0,
            15.0 to 180.0,
        )
        val verdict = DescentRedoseGuard.evaluate(quick, nowMs = at(20.0), iobU = 10.0)

        assertThat(verdict.block).isFalse()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_PEAK_TOO_RECENT)
    }

    @Test
    fun `condition c alone missing - BG has fallen only 10 from the peak`() {
        val verdict = DescentRedoseGuard.evaluate(
            descent(peak = 232.5, trough = 224.0, current = 222.5), nowMs = at(42.0), iobU = 10.0,
        )

        assertThat(verdict.block).isFalse()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_DROP_TOO_SMALL)
    }

    @Test
    fun `condition d alone missing - only 5 U still active`() {
        val verdict = DescentRedoseGuard.evaluate(fallingDescent, nowMs = at(42.0), iobU = 5.0)

        assertThat(verdict.block).isFalse()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_IOB_TOO_LOW)
    }

    @Test
    fun `condition e alone missing - BG is 30 above the trough`() {
        val verdict = DescentRedoseGuard.evaluate(
            descent(peak = 232.5, trough = 170.0, current = 200.0), nowMs = at(42.0), iobU = 10.0,
        )

        assertThat(verdict.block).isFalse()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_REBOUND_ABOVE_TROUGH)
    }

    @Test
    fun `condition f alone missing - BG is going up again`() {
        val bump = series(
            0.0 to 232.5, // peak
            10.0 to 200.0,
            20.0 to 180.0,
            28.0 to 172.0,
            34.0 to 176.0,
            40.0 to 182.0, // rising again, but only +10 over the trough so (e) still holds
        )
        val verdict = DescentRedoseGuard.evaluate(bump, nowMs = at(42.0), iobU = 10.0)

        assertThat(verdict.block).isFalse()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_BG_RISING)
        assertThat(verdict.reboundFromTroughMgdl!!).isAtMost(DescentRedoseGuard.REBOUND_MGDL)
        assertThat(verdict.slopeMgdlPerMin!!).isGreaterThan(0.0)
    }

    @Test
    fun `exactly on each threshold the guard still blocks`() {
        // The conditions use >= and <=, so a tick sitting on a threshold is inside, not outside.
        val onEdge = series(
            0.0 to 180.0,  // (a) exactly PEAK_MIN_MGDL
            10.0 to 170.0,
            20.0 to 160.0,
            25.0 to 130.0, // trough
            30.0 to 155.0, // (c) drop is exactly 25, (e) rebound is exactly 25
        )
        val verdict = DescentRedoseGuard.evaluate(onEdge, nowMs = at(30.0), iobU = 6.0)

        assertThat(verdict.block).isTrue()
        assertThat(verdict.dropFromPeakMgdl).isWithin(1e-9).of(25.0)
        assertThat(verdict.reboundFromTroughMgdl).isWithin(1e-9).of(25.0)
        assertThat(verdict.peakAgeMinutes).isWithin(1e-9).of(30.0)
    }

    // ---------------------------------------------------------------------------------------
    // Arming: the verdict is measured on every tick, the dose is only touched when the key is on.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `with the key off the verdict is still computed but no dose is withheld`() {
        val verdict = DescentRedoseGuard.evaluate(fallingDescent, nowMs = at(42.0), iobU = 10.0)

        // The shadow measurement happens whatever the key says.
        assertThat(verdict.block).isTrue()
        assertThat(verdict.reasonCode).isEqualTo(DescentRedoseGuard.REASON_BLOCKED)

        val withheld = DescentRedoseGuard.shouldWithhold(
            verdict = verdict, armed = false, isExplicitUserAction = false, proposedUnits = 0.55,
        )
        assertThat(withheld).isFalse()
    }

    @Test
    fun `with the key on the same tick withholds the dose`() {
        val verdict = DescentRedoseGuard.evaluate(fallingDescent, nowMs = at(42.0), iobU = 10.0)

        val withheld = DescentRedoseGuard.shouldWithhold(
            verdict = verdict, armed = true, isExplicitUserAction = false, proposedUnits = 0.55,
        )
        assertThat(withheld).isTrue()
    }

    @Test
    fun `a user-initiated bolus is never withheld even with the key on`() {
        val verdict = DescentRedoseGuard.evaluate(fallingDescent, nowMs = at(42.0), iobU = 10.0)

        val withheld = DescentRedoseGuard.shouldWithhold(
            verdict = verdict, armed = true, isExplicitUserAction = true, proposedUnits = 0.55,
        )
        assertThat(withheld).isFalse()
    }

    @Test
    fun `a tick that already proposes nothing is not reported as a withholding`() {
        val verdict = DescentRedoseGuard.evaluate(fallingDescent, nowMs = at(42.0), iobU = 10.0)

        val withheld = DescentRedoseGuard.shouldWithhold(
            verdict = verdict, armed = true, isExplicitUserAction = false, proposedUnits = 0.0,
        )
        assertThat(withheld).isFalse()
    }

    @Test
    fun `the good 09-09 lunch is not withheld even with the key on`() {
        val verdict = DescentRedoseGuard.evaluate(lunch0909, nowMs = at(77.0), iobU = 9.0)

        val withheld = DescentRedoseGuard.shouldWithhold(
            verdict = verdict, armed = true, isExplicitUserAction = false, proposedUnits = 1.20,
        )
        assertThat(withheld).isFalse()
    }

    @Test
    fun `a rising tick of 11-09 is not withheld even with the key on`() {
        val verdict = verdict1109(rise1109.indices.last)

        val withheld = DescentRedoseGuard.shouldWithhold(
            verdict = verdict, armed = true, isExplicitUserAction = false, proposedUnits = 0.55,
        )
        assertThat(withheld).isFalse()
    }

    @Test
    fun `the reason string always starts with the reason code so it can be counted`() {
        val blocked = DescentRedoseGuard.evaluate(fallingDescent, nowMs = at(42.0), iobU = 10.0)
        val passed = DescentRedoseGuard.evaluate(lunch0909, nowMs = at(77.0), iobU = 9.0)

        assertThat(blocked.reason).startsWith(DescentRedoseGuard.REASON_BLOCKED)
        assertThat(passed.reason).startsWith(DescentRedoseGuard.REASON_DROP_TOO_SMALL)
        assertThat(blocked.reason).contains("peak=232.5")
        assertThat(blocked.reason).contains("iob=10.00")
    }

    @Test
    fun `the reason string carries the slope so the export can be read`() {
        val blocked = DescentRedoseGuard.evaluate(fallingDescent, nowMs = at(42.0), iobU = 10.0)
        val rising = DescentRedoseGuard.evaluate(night0809, nowMs = at(57.0), iobU = 13.81)
        val unknown = DescentRedoseGuard.evaluate(emptyList(), nowMs = at(10.0), iobU = 20.0)

        assertThat(blocked.reason).contains("slope=-0.33")
        assertThat(rising.reason).contains("slope=1.28")
        // An empty window has no slope at all, and the short reason string says so by leaving it out.
        assertThat(unknown.reason).isEqualTo("no_data iob=20.00")
    }
}
