package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InsulinStackingStanceTest {

    @Test
    fun `surveillance when plateau high IOB and eventual well below BG`() {
        val e = InsulinStackingStance.evaluate(
            bg = 152.0,
            delta = 1.0,
            shortAvgDelta = 1.5,
            targetBg = 100.0,
            iob = 6.5,
            maxIob = 20.0,
            eventualBg = 104.0,
            minPredBg = 100.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.SURVEILLANCE_IOB, e.kind)
        assertTrue(e.suppressRedCarpetRestore)
        assertEquals(0.32, e.smbMultiplier, 1e-6)
        assertEquals(0.38, e.smbAbsoluteCapU, 1e-6)
    }

    @Test
    fun `active correction on sharp rise despite high IOB`() {
        val e = InsulinStackingStance.evaluate(
            bg = 160.0,
            delta = 5.0,
            shortAvgDelta = 4.0,
            targetBg = 100.0,
            iob = 7.0,
            maxIob = 20.0,
            eventualBg = 200.0,
            minPredBg = 180.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
        assertFalse(e.suppressRedCarpetRestore)
    }

    @Test
    fun `active when IOB below floor`() {
        val e = InsulinStackingStance.evaluate(
            bg = 150.0,
            delta = 0.5,
            shortAvgDelta = 1.0,
            targetBg = 100.0,
            iob = 2.0,
            maxIob = 20.0,
            eventualBg = 90.0,
            minPredBg = 85.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
    }

    @Test
    fun `surveillance from trajectory energy alone`() {
        val e = InsulinStackingStance.evaluate(
            bg = 148.0,
            delta = 1.2,
            shortAvgDelta = 2.0,
            targetBg = 100.0,
            iob = 5.0,
            maxIob = 18.0,
            eventualBg = null,
            minPredBg = null,
            trajectoryEnergy = 2.4,
            isExplicitUserAction = false,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.SURVEILLANCE_IOB, e.kind)
    }

    @Test
    fun `disabled falls through to active`() {
        val e = InsulinStackingStance.evaluate(
            bg = 152.0,
            delta = 0.5,
            shortAvgDelta = 1.0,
            targetBg = 100.0,
            iob = 6.0,
            maxIob = 20.0,
            eventualBg = 95.0,
            minPredBg = 90.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = false
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
    }

    @Test
    fun `explicit user action bypasses`() {
        val e = InsulinStackingStance.evaluate(
            bg = 152.0,
            delta = 0.5,
            shortAvgDelta = 1.0,
            targetBg = 100.0,
            iob = 6.0,
            maxIob = 20.0,
            eventualBg = 90.0,
            minPredBg = 85.0,
            trajectoryEnergy = 3.0,
            isExplicitUserAction = true,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
    }

    @Test
    fun `meal priority with clear absorption rise bypasses surveillance`() {
        val e = InsulinStackingStance.evaluate(
            bg = 155.0,
            delta = 2.2,
            shortAvgDelta = 2.0,
            targetBg = 100.0,
            iob = 6.0,
            maxIob = 20.0,
            eventualBg = 92.0,
            minPredBg = 88.0,
            trajectoryEnergy = 2.5,
            isExplicitUserAction = false,
            enabled = true,
            mealPriorityContext = true
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
        assertEquals("meal_absorption_rise_priority", e.activeReason)
    }

    @Test
    fun `meal priority on mild plateau still allows surveillance when signals fire`() {
        val e = InsulinStackingStance.evaluate(
            bg = 152.0,
            delta = 1.0,
            shortAvgDelta = 1.6,
            targetBg = 100.0,
            iob = 6.5,
            maxIob = 20.0,
            eventualBg = 104.0,
            minPredBg = 100.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true,
            mealPriorityContext = true
        )
        assertEquals(InsulinStackingStance.Kind.SURVEILLANCE_IOB, e.kind)
    }
}
