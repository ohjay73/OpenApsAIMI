package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LgsSafetyTriageTest {

    private val basal = 1.2

    @Test
    fun tier1WhenBgBelowThreshold() {
        val r = resolveSafetyStart(
            bg = 60.0,
            delta = 0f,
            noise = 0,
            predBg = 100.0,
            eventualBg = 100.0,
            currentBasalUph = basal,
            lgsThreshold = 70,
        )
        assertTrue(r.decision is DecisionResult.Applied)
        val a = r.decision as DecisionResult.Applied
        assertEquals("SafetyLGS_T1", a.source)
        assertEquals(0.0, a.tbrUph!!, 1e-9)
        assertEquals("SafetyLGS_T1", r.lastSafetySource)
        assertTrue(r.consoleLines.any { it.contains("SAFETY_LGS_TIER1") })
    }

    @Test
    fun tier2WhenPredBelowThresholdAndBgOk() {
        val r = resolveSafetyStart(
            bg = 100.0,
            delta = 0f,
            noise = 0,
            predBg = 65.0,
            eventualBg = 100.0,
            currentBasalUph = basal,
            lgsThreshold = 70,
        )
        val a = r.decision as DecisionResult.Applied
        assertEquals("SafetyLGS_T2", a.source)
        assertEquals(basal * 0.25, a.tbrUph!!, 1e-9)
        assertEquals("SafetyLGS_T2", r.lastSafetySource)
    }

    @Test
    fun tier3WhenEventualBelowThreshold() {
        val r = resolveSafetyStart(
            bg = 100.0,
            delta = 0f,
            noise = 0,
            predBg = 100.0,
            eventualBg = 65.0,
            currentBasalUph = basal,
            lgsThreshold = 70,
        )
        val a = r.decision as DecisionResult.Applied
        assertEquals("SafetyLGS_T3", a.source)
        assertEquals(basal * 0.50, a.tbrUph!!, 1e-9)
    }

    @Test
    fun noiseBlocksBeforeFallthrough() {
        val r = resolveSafetyStart(
            bg = 150.0,
            delta = 0f,
            noise = 3,
            predBg = 150.0,
            eventualBg = 150.0,
            currentBasalUph = basal,
            lgsThreshold = 70,
        )
        val a = r.decision as DecisionResult.Applied
        assertEquals("SafetyNoise", a.source)
        assertEquals("SafetyNoise", r.lastSafetySource)
    }

    @Test
    fun fallthroughWhenSafe() {
        val r = resolveSafetyStart(
            bg = 150.0,
            delta = 0f,
            noise = 0,
            predBg = 150.0,
            eventualBg = 150.0,
            currentBasalUph = basal,
            lgsThreshold = 70,
        )
        assertTrue(r.decision is DecisionResult.Fallthrough)
        assertEquals("SafetyPass", r.lastSafetySource)
    }

    @Test
    fun unitRangeWarningPrepended() {
        val r = resolveSafetyStart(
            bg = 800.0,
            delta = 0f,
            noise = 0,
            predBg = 150.0,
            eventualBg = 150.0,
            currentBasalUph = basal,
            lgsThreshold = 70,
        )
        assertTrue(r.consoleLines.first().contains("Unit Mismatch"))
        assertTrue(r.decision is DecisionResult.Fallthrough)
    }
}
