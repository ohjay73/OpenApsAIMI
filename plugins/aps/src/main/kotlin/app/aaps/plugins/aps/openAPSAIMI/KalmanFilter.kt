package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.Preferences
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlin.math.abs
import kotlin.math.ln

/**
 * Classe de filtre de Kalman simple permettant de lisser les mesures d'ISF.
 */
class KalmanFilter(
    var stateEstimate: Double,
    var estimationError: Double,
    var processVariance: Double,
    var measurementVariance: Double
) {
    /**
     * Mise à jour du filtre avec une nouvelle mesure.
     */
    fun update(measurement: Double): Double {
        // Étape de prédiction
        val prediction = stateEstimate
        val predictionError = estimationError + processVariance

        // Calcul du gain de Kalman
        val kalmanGain = predictionError / (predictionError + measurementVariance)

        // Mise à jour de l'estimation de l'état
        stateEstimate = prediction + kalmanGain * (measurement - prediction)
        estimationError = (1 - kalmanGain) * predictionError

        return stateEstimate
    }
}

/**
 * Calculateur de l’ISF utilisant une approche par filtre de Kalman.
 *
 * L’estimation de l’ISF se base sur une mesure brute obtenue avec une formule classique,
 * puis le filtre de Kalman lisse cette valeur pour obtenir une estimation plus stable et réactive.
 */
class KalmanISFCalculator(
    private val tddCalculator: TddCalculator,
    private val preferences: Preferences,
    private val logger: AAPSLogger
) {
    companion object {
        private const val MIN_ISF = 5.0
        private const val MAX_ISF = 300.0
        private const val BASE_CONSTANT = 75.0
        private const val SCALING_FACTOR = 1800.0
    }

    // Augmente la variance de processus pour plus de réactivité
    private val kalmanFilter = KalmanFilter(
        stateEstimate = 15.0,
        estimationError = 5.0,
        processVariance = 5.0,      // Augmenté (était 0.5)
        measurementVariance = 2.0
    )

    private fun computeEffectiveTDD(): Double {
        val tdd7P = preferences.get(DoubleKey.OApsAIMITDD7)
        val tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.data?.totalAmount ?: tdd7P
        val tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.data?.totalAmount ?: tdd7P
        val tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount ?: tdd7P
        return (0.2 * tdd7D) + (0.4 * tdd2Days) + (0.4 * tddDaily)
    }

    // private fun computeRawISF(glucose: Double): Double {
    //     val effectiveTDD = computeEffectiveTDD()
    //     val safeTDD = if (effectiveTDD < 1.0) 1.0 else effectiveTDD
    //     // Facteur additionnel : si la glycémie dépasse 200 mg/dL, on réduit rawISF
    //     val bgFactor = if (glucose > 200.0) 0.7 else 1.0
    //     val rawISF = SCALING_FACTOR / (safeTDD * ln(glucose / BASE_CONSTANT + 1)) * bgFactor
    //     return rawISF.coerceIn(MIN_ISF, MAX_ISF)
    // }
    private fun computeRawISF(glucose: Double): Double {
        val effectiveTDD = computeEffectiveTDD()
        val safeTDD = if (effectiveTDD < 1.0) 1.0 else effectiveTDD
        // Facteur BG : si glucose > 130, on applique une réduction progressive
        val bgFactor = if (glucose > 130.0) {
            // On déduit une réduction linéaire jusqu’à un minimum de 0.5 pour BG >= 250
            val factor = 1.0 - ((glucose - 130.0) / (200.0 - 130.0)) * 0.5
            factor.coerceAtLeast(0.5)
        } else {
            1.0
        }
        // On applique bgFactor à la formule classique
        val rawISF = SCALING_FACTOR / (safeTDD * ln(glucose / BASE_CONSTANT + 1)) * bgFactor
        return rawISF.coerceIn(MIN_ISF, MAX_ISF)
    }


    fun calculateISF(glucose: Double, currentDelta: Double?, predictedDelta: Double?): Double {
        val rawISF = computeRawISF(glucose)
        logger.debug(LTag.APS, "Raw ISF calculé : $rawISF pour BG = $glucose")

        // Ajuster measurementVariance pour être plus réactif en cas de forte variation
        kalmanFilter.measurementVariance = when {
            currentDelta != null && abs(currentDelta) > 10 -> 1.0
            currentDelta != null && abs(currentDelta) > 5 -> 1.5
            else -> 2.0
        }

        val filteredISF = kalmanFilter.update(rawISF).coerceIn(MIN_ISF, MAX_ISF)
        logger.debug(LTag.APS, "ISF filtré par Kalman : $filteredISF (variance de mesure = ${kalmanFilter.measurementVariance})")
        return filteredISF
    }
}
