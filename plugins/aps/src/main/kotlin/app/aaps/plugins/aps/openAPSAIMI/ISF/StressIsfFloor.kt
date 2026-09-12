package app.aaps.plugins.aps.openAPSAIMI.ISF

import java.util.Locale

/**
 * Answers one question: does the stress signature hold right now, and has it held long enough?
 *
 * ## Why this exists
 *
 * The user's profile sensitivity is **120 mg/dL/U from 00:00 to 11:00 and 50 from 11:00 to 00:00**.
 * The 120 is a deliberate safety choice for sleep and for the morning cortisol peak. Measured on the
 * support-package corpus, the dynamic chain commands about **0.56 x that profile on 3858 night ticks
 * out of 3870 (100 %)**. The existing bound [DynamicSensitivityPolicy.PROFILE_RELATIVE_FLOOR] caps the
 * drift at half the profile, it does not undo it.
 *
 * On **15 morning stress episodes** found in the same corpus (heart rate at least 20 bpm over resting,
 * fewer than 100 steps in 15 min, held at least 10 min), the commanded sensitivity sat at **0.50 to
 * 0.68 x profile, median 0.57**. A floor at 1.0 x profile during those episodes would have withheld
 * **3.10 U per episode on the 2 that ended below 80 mg/dL**, against **0.72 U** on the 13 that ended
 * well — a separation ratio of **4.29** (5.23 at the 70 mg/dL threshold). Seven other candidate
 * gestures measured that same week separated between 0.64 and 1.25 and were dropped.
 *
 * ## What this object is, and is not
 *
 * It is pure: it holds no state, reads no clock and touches no preference. The caller keeps
 * [Verdict.signatureSinceMs] and [Verdict.lastEvaluatedMs] and feeds them back on the next tick. It
 * decides nothing about insulin either — it only reports the signature. The dose side of the gesture
 * is a floor multiplier handed to [DynamicSensitivityPolicy.floorAgainstProfile], and raising the
 * commanded sensitivity can only make a dose smaller, never larger.
 *
 * There is **no time window**: the signature is evaluated 24 hours a day.
 */
object StressIsfFloor {

    /**
     * How far the heart rate must sit above the resting rate, in bpm.
     *
     * The 15 measured episodes were selected with this threshold. On the real case of 2026-09-12 the
     * heart rate was 82 against a resting rate of 50, an excess of 32.
     */
    const val HR_ABOVE_RESTING_BPM: Int = 20

    /**
     * Steps in the last 15 minutes that still count as "not walking".
     *
     * This is what separates a cortisol rise from exercise. Above it the heart rate is explained by
     * movement, and movement is already handled by the effort and activity path. The 15 episodes all
     * sat well under it; the 2026-09-12 case had 48 steps.
     */
    const val MAX_STEPS_LAST_15M: Int = 100

    /**
     * How long the signature must hold, in minutes, before the floor is allowed to act.
     *
     * A single elevated heart-rate sample is noise — a stair climb, a bad contact, a startle. Ten
     * minutes is two CGM ticks, and it is the hold time used to select the 15 measured episodes.
     */
    const val MIN_HELD_MINUTES: Double = 10.0

    /**
     * Longest gap, in ms, that two evaluations may have between them and still count as continuous.
     *
     * A larger gap means the loop was not running: a restart, a data hole, a phone asleep. Nothing was
     * observed during it, so the hold time cannot be claimed. Two CGM ticks, same as
     * [MIN_HELD_MINUTES], so one missed tick is tolerated and two are not.
     */
    const val MAX_GAP_BETWEEN_EVALUATIONS_MS: Long = 10 * 60 * 1000L

    /**
     * Floor multiplier the caller passes to [DynamicSensitivityPolicy.floorAgainstProfile] while the
     * signature is active and the key is armed: the commanded sensitivity may not fall under the
     * profile sensitivity of this time of day.
     */
    const val ARMED_FLOOR_MULTIPLIER: Double = 1.0

    /** Heart rate or resting heart rate missing from the export. Never activates anything. */
    const val REASON_NO_HR: String = "no_hr"

    /** The signature does not hold at this instant. */
    const val REASON_NO_SIGNATURE: String = "no_signature"

    /** The signature holds, but a gap between two evaluations reset the hold time. */
    const val REASON_GAP_RESTART: String = "gap_restart"

    /** The signature holds and the hold time is still building up. */
    const val REASON_HOLDING: String = "holding"

    /** The signature holds and has held for at least [MIN_HELD_MINUTES]. */
    const val REASON_ACTIVE: String = "active"

    /**
     * What the signature looks like at one instant.
     *
     * @param active true only when the signature holds **and** has held for at least
     *   [MIN_HELD_MINUTES] without a break.
     * @param heldMinutes how long the signature has held, in minutes. 0.0 whenever it does not hold.
     * @param signatureSinceMs the instant the current unbroken signature started, or null when there
     *   is none. The caller stores it and feeds it back on the next tick.
     * @param lastEvaluatedMs the instant of this evaluation. The caller stores it and feeds it back on
     *   the next tick so a gap can be detected.
     * @param reason a code taken from [REASON_NO_HR], [REASON_NO_SIGNATURE], [REASON_GAP_RESTART],
     *   [REASON_HOLDING] or [REASON_ACTIVE], followed by the live values that produced it.
     */
    data class Verdict(
        val active: Boolean,
        val heldMinutes: Double,
        val signatureSinceMs: Long?,
        val lastEvaluatedMs: Long,
        val reason: String,
    )

    /**
     * Evaluates the stress signature for one tick.
     *
     * Hard rules, in order:
     * 1. A heart rate or a resting heart rate of zero or less is **missing data**, not a calm patient.
     *    In this export an absent heart rate is written as 0. Missing data never activates a gesture,
     *    so the verdict is inactive with [REASON_NO_HR].
     * 2. The signature must hold **without a break**. The moment it stops holding, the start instant
     *    goes back to null and the hold time goes back to zero.
     * 3. More than [MAX_GAP_BETWEEN_EVALUATIONS_MS] between two evaluations breaks continuity, because
     *    nothing was observed in between. The hold time restarts from now.
     *
     * @param hrNowBpm the most recent heart rate, bpm. 0 when unknown.
     * @param rhrRestingBpm the resting heart rate, bpm. 0 when unknown.
     * @param stepsLast15m steps counted in the last 15 minutes.
     * @param nowMs the instant of this evaluation.
     * @param signatureSinceMs the value of [Verdict.signatureSinceMs] returned by the previous call,
     *   or null at start-up.
     * @param lastEvaluatedMs the value of [Verdict.lastEvaluatedMs] returned by the previous call, or
     *   null at start-up. A null means no gap can be measured, so continuity is assumed.
     */
    fun evaluate(
        hrNowBpm: Int,
        rhrRestingBpm: Int,
        stepsLast15m: Int,
        nowMs: Long,
        signatureSinceMs: Long?,
        lastEvaluatedMs: Long? = null,
    ): Verdict {
        if (hrNowBpm <= 0 || rhrRestingBpm <= 0) {
            return Verdict(
                active = false,
                heldMinutes = 0.0,
                signatureSinceMs = null,
                lastEvaluatedMs = nowMs,
                reason = "$REASON_NO_HR hr=$hrNowBpm rest=$rhrRestingBpm steps15=$stepsLast15m",
            )
        }

        val excessBpm = hrNowBpm - rhrRestingBpm
        val signatureHolds = excessBpm >= HR_ABOVE_RESTING_BPM && stepsLast15m < MAX_STEPS_LAST_15M
        val values = "hr=$hrNowBpm rest=$rhrRestingBpm excess=$excessBpm steps15=$stepsLast15m"

        if (!signatureHolds) {
            return Verdict(
                active = false,
                heldMinutes = 0.0,
                signatureSinceMs = null,
                lastEvaluatedMs = nowMs,
                reason = "$REASON_NO_SIGNATURE $values",
            )
        }

        // A gap, a clock that went backwards, or a first evaluation all mean the hold time restarts.
        val gapMs = lastEvaluatedMs?.let { nowMs - it }
        val continuityBroken = gapMs != null && (gapMs < 0L || gapMs > MAX_GAP_BETWEEN_EVALUATIONS_MS)
        val startedMs = when {
            continuityBroken            -> nowMs
            signatureSinceMs == null    -> nowMs
            signatureSinceMs > nowMs    -> nowMs
            else                        -> signatureSinceMs
        }
        val heldMinutes = ((nowMs - startedMs) / 60_000.0).coerceAtLeast(0.0)
        val active = heldMinutes >= MIN_HELD_MINUTES
        val code = when {
            active            -> REASON_ACTIVE
            continuityBroken  -> REASON_GAP_RESTART
            else              -> REASON_HOLDING
        }
        val heldText = String.format(Locale.ROOT, "held=%.1fmin", heldMinutes)
        return Verdict(
            active = active,
            heldMinutes = heldMinutes,
            signatureSinceMs = startedMs,
            lastEvaluatedMs = nowMs,
            reason = "$code $values $heldText",
        )
    }
}
