package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * Insulin action presets for the guided PK/PD screen.
 * Values are clamped to each [DoubleKey] min/max before storage.
 */
enum class PkpdInsulinPreset {
    ULTRA_FAST,
    RAPID,
    STANDARD,
    CUSTOM,
}

private fun Preferences.putClamped(key: DoubleKey, value: Double) {
    put(key, value.coerceIn(key.min, key.max))
}

/**
 * Writes initial PK/PD parameters and learning bounds, then aligns learned state
 * so the loop immediately uses values consistent with the preset.
 */
fun applyPkpdInsulinPreset(preferences: Preferences, preset: PkpdInsulinPreset) {
    if (preset == PkpdInsulinPreset.CUSTOM) return
    when (preset) {
        PkpdInsulinPreset.ULTRA_FAST -> {
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsDiaMinH, 5.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH, 8.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin, 35.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax, 95.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdInitialDiaH, 6.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdInitialPeakMin, 55.0)
        }
        PkpdInsulinPreset.RAPID -> {
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsDiaMinH, 5.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH, 11.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin, 50.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax, 130.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdInitialDiaH, 6.5)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdInitialPeakMin, 75.0)
        }
        PkpdInsulinPreset.STANDARD -> {
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsDiaMinH, 6.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH, 16.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin, 65.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax, 200.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdInitialDiaH, 8.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdInitialPeakMin, 90.0)
        }
        PkpdInsulinPreset.CUSTOM -> Unit
    }
    syncPkpdLearnedStateToBounds(preferences)
}

/**
 * Clamps [DoubleKey.OApsAIMIPkpdStateDiaH] / [DoubleKey.OApsAIMIPkpdStatePeakMin] to
 * current initial + bounds (and key limits).
 */
fun syncPkpdLearnedStateToBounds(preferences: Preferences) {
    val diaInit = preferences.get(DoubleKey.OApsAIMIPkpdInitialDiaH)
    val peakInit = preferences.get(DoubleKey.OApsAIMIPkpdInitialPeakMin)
    val bDiaLo = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH)
    val bDiaHi = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH)
    val bPeakLo = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin)
    val bPeakHi = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax)
    val dia = diaInit.coerceIn(bDiaLo, bDiaHi)
    val peak = peakInit.coerceIn(bPeakLo, bPeakHi)
    preferences.putClamped(DoubleKey.OApsAIMIPkpdStateDiaH, dia)
    preferences.putClamped(DoubleKey.OApsAIMIPkpdStatePeakMin, peak)
}
