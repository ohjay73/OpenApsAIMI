package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * Centralised hypo / prediction guard used by [app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAIMI2]
 * and [HighBgOverride]. Logic is intentionally identical to the former private
 * `DetermineBasalAIMI2.isBelowHypoThreshold` implementation (delta bypass, floor = hypo − 5, fast-fall).
 */
object HypoGuard {

    /**
     * @param hypo effective hypo guard threshold (mg/dL), not the raw floor; caller supplies [HypoThresholdMath] output when needed.
     */
    fun isBelowHypoThreshold(
        bgNow: Double,
        predicted: Double,
        eventual: Double,
        hypo: Double,
        delta: Double,
    ): Boolean {
        val tol = 5.0
        val floor = hypo - tol

        // 1. Hypo actuelle = TOUJOURS bloquer (sécurité absolue)
        val strongNow = bgNow <= floor
        if (strongNow) return true

        // 2. Bypass progressif si BG monte clairement
        //    - delta >= 4 : bypass total des prédictions (montée forte)
        //    - delta >= 2 && bg > hypo : bypass strongFuture seulement
        val risingFast = delta >= 4.0
        val risingModerate = delta >= 2.0 && bgNow > hypo

        if (risingFast) {
            return false
        }

        // 3. Prédictions futures (seulement si pas en montée modérée)
        val strongFuture = (predicted <= floor && eventual <= floor)
        if (strongFuture && risingModerate) {
            // Continue to check fastFall only
        } else if (strongFuture) {
            return true
        }

        // 4. Chute rapide avec prédiction basse
        val fastFall = (delta <= -2.0 && predicted <= hypo)
        return fastFall
    }
}
