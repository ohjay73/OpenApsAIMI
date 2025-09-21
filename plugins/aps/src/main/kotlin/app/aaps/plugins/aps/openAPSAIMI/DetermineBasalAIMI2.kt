package app.aaps.plugins.aps.openAPSAIMI

import android.annotation.SuppressLint
import android.os.Environment
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.UE
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import android.content.Context
import kotlin.math.exp


@Singleton
class DetermineBasalaimiSMB2 @Inject constructor(
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    private val context: Context
) {
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var tirCalculator: TirCalculator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var activePlugin: ActivePlugin

    private val EPS_FALL = 0.3      // mg/dL/5min : seuil de baisse
    private val EPS_ACC  = 0.2      // mg/dL/5min : seuil d'écart short vs long
    // — Hypo guard —
    private val HYPO_MARGIN_BASE = 0.0     // mg/dL
    private val HYPO_MARGIN_FALL  = 5.0    // delta <= -1.5 mg/dL/5min
    private val HYPO_MARGIN_FAST  = 10.0   // delta <= -3.0 mg/dL/5min

    // — Hystérèse anti-pompage —
    private val HYPO_RELEASE_MARGIN   = 5.0      // mg/dL au-dessus du seuil
    private val HYPO_RELEASE_HOLD_MIN = 5        // minutes à rester > seuil+margin

    // État interne d’hystérèse
    private var lastHypoBlockAt: Long = 0L
    private var hypoClearCandidateSince: Long? = null

    private val consoleError = mutableListOf<String>()
    private val consoleLog = mutableListOf<String>()
    private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    //private val modelFile = File(externalDir, "ml/model.tflite")
    //private val modelFileUAM = File(externalDir, "ml/modelUAM.tflite")
    private val csvfile = File(externalDir, "oapsaimiML2_records.csv")
    private val csvfile2 = File(externalDir, "oapsaimi2_records.csv")
    //private val tempFile = File(externalDir, "temp.csv")
    private var bgacc = 0.0
    private var predictedSMB = 0.0f
    private var variableSensitivity = 0.0f
    private var averageBeatsPerMinute = 0.0
    private var averageBeatsPerMinute10 = 0.0
    private var averageBeatsPerMinute60 = 0.0
    private var averageBeatsPerMinute180 = 0.0
    private var eventualBG = 0.0
    private var now = System.currentTimeMillis()
    private var iob = 0.0f
    private var cob = 0.0f
    private var predictedBg = 0.0f
    private var lastCarbAgeMin: Int = 0
    private var futureCarbs = 0.0f
    //private var enablebasal: Boolean = false
    private var recentNotes: List<UE>? = null
    private var tags0to60minAgo = ""
    private var tags60to120minAgo = ""
    private var tags120to180minAgo = ""
    private var tags180to240minAgo = ""
    private var tir1DAYabove: Double = 0.0
    private var currentTIRLow: Double = 0.0
    private var currentTIRRange: Double = 0.0
    private var currentTIRAbove: Double = 0.0
    private var lastHourTIRLow: Double = 0.0
    private var lastHourTIRLow100: Double = 0.0
    private var lastHourTIRabove170: Double = 0.0
    private var lastHourTIRabove120: Double = 0.0
    private var bg = 0.0
    private var targetBg = 90.0f
    private var normalBgThreshold = 110.0f
    private var delta = 0.0f
    private var shortAvgDelta = 0.0f
    private var longAvgDelta = 0.0f
    private var lastsmbtime = 0
    private var acceleratingUp: Int = 0
    private var decceleratingUp: Int = 0
    private var acceleratingDown: Int = 0
    private var decceleratingDown: Int = 0
    private var stable: Int = 0
    private var maxIob = 0.0
    private var maxSMB = 1.0
    private var maxSMBHB = 1.0
    private var lastBolusSMBUnit = 0.0f
    private var tdd7DaysPerHour = 0.0f
    private var tdd2DaysPerHour = 0.0f
    private var tddPerHour = 0.0f
    private var tdd24HrsPerHour = 0.0f
    private var hourOfDay: Int = 0
    private var weekend: Int = 0
    private var recentSteps5Minutes: Int = 0
    private var recentSteps10Minutes: Int = 0
    private var recentSteps15Minutes: Int = 0
    private var recentSteps30Minutes: Int = 0
    private var recentSteps60Minutes: Int = 0
    private var recentSteps180Minutes: Int = 0
    private var basalaimi = 0.0f
    private var aimilimit = 0.0f
    private var ci = 0.0f
    private var sleepTime = false
    private var sportTime = false
    private var snackTime = false
    private var lowCarbTime = false
    private var highCarbTime = false
    private var mealTime = false
    private var bfastTime = false
    private var lunchTime = false
    private var dinnerTime = false
    private var fastingTime = false
    private var stopTime = false
    private var iscalibration = false
    private var mealruntime: Long = 0
    private var bfastruntime: Long = 0
    private var lunchruntime: Long = 0
    private var dinnerruntime: Long = 0
    private var highCarbrunTime: Long = 0
    private var snackrunTime: Long = 0
    private var intervalsmb = 1
    private var peakintermediaire = 0.0
    private var insulinPeakTime = 0.0
    private var zeroBasalAccumulatedMinutes: Int = 0
    private val MAX_ZERO_BASAL_DURATION = 60  // Durée maximale autorisée en minutes à 0 basal

    private fun Double.toFixed2(): String = DecimalFormat("0.00#").format(round(this, 2))
    /**
     * Prédit l’évolution de la glycémie sur un horizon donné (en minutes),
     * avec des pas de 5 minutes.
     *
     * @param currentBG La glycémie actuelle (mg/dL)
     * @param basalCandidate La dose basale candidate (en U/h)
     * @param horizonMinutes L’horizon de prédiction (ex. 30 minutes)
     * @param insulinSensitivity La sensibilité insulinique (mg/dL/U)
     * @return Une liste de glycémies prédites pour chaque pas de 5 minutes.
     */
    private fun predictGlycemia(
        currentBG: Double,
        basalCandidateUph: Double,
        horizonMinutes: Int,
        insulinSensitivityMgdlPerU: Double,
        stepMinutes: Int = 5,
        minBgClamp: Double = 40.0,
        maxBgClamp: Double = 400.0,
        // ↓ nouveaux paramètres optionnels (par défaut 5h de DIA, pic à 75 min)
        diaMinutes: Int = 300,
        timeToPeakMinutes: Int = 75
    ): List<Double> {
        val predictions = ArrayList<Double>(maxOf(0, horizonMinutes / stepMinutes))
        if (horizonMinutes <= 0 || stepMinutes <= 0) return predictions

        var bg = currentBG
        val steps = horizonMinutes / stepMinutes
        val uPerStep = basalCandidateUph * (stepMinutes / 60.0)

        fun triangularActivity(tMin: Int, tp: Int, dia: Int): Double {
            if (tMin <= 0 || tMin >= dia) return 0.0
            val tpClamped = tp.coerceIn(1, dia - 1)
            val rise = if (tMin <= tpClamped) (2.0 / tpClamped) * tMin else 0.0
            val fall = if (tMin > tpClamped) 2.0 * (1.0 - (tMin - tpClamped).toDouble() / (dia - tpClamped)) else 0.0
            // Hauteur max = 2.0 → aire totale sur [0, DIA] ≈ DIA (même “dose” qu’activité = 1)
            return if (tMin <= tpClamped) rise else fall
        }

        repeat(steps) { k ->
            val tMin = (k + 1) * stepMinutes

            // activité réaliste (pic à tp, s’éteint à DIA)
            val activity = triangularActivity(tMin, timeToPeakMinutes, diaMinutes)

            // effet du pas courant (pas de convolution pour rester simple comme ton code)
            val delta = insulinSensitivityMgdlPerU * uPerStep * activity

            bg = (bg - delta).coerceIn(minBgClamp, maxBgClamp)
            predictions.add(bg)

            // early stop en hypo profonde
            if (bg <= minBgClamp) return predictions
        }
        return predictions
    }

    /**
     * Calcule la fonction de coût, ici la somme des carrés des écarts entre les glycémies prédites et la glycémie cible.
     *
     * @param basalCandidate La dose candidate de basal.
     * @param currentBG La glycémie actuelle.
     * @param targetBG La glycémie cible.
     * @param horizonMinutes L’horizon de prédiction (en minutes).
     * @param insulinSensitivity La sensibilité insulinique.
     * @return Le coût cumulé.
     */
    fun costFunction(
        basalCandidate: Double, currentBG: Double,
        targetBG: Double, horizonMinutes: Int,
        insulinSensitivity: Double, nnPrediction: Double
    ): Double {
        val predictions = predictGlycemia(currentBG, basalCandidate, horizonMinutes, insulinSensitivity)
        val predictionCost = predictions.sumOf { (it - targetBG).pow(2) }
        val nnPenalty = (basalCandidate - nnPrediction).pow(2)
        return predictionCost + 0.5 * nnPenalty  // Pondération du terme de pénalité
    }


    private fun roundBasal(value: Double): Double = value
    private fun getZeroBasalDuration(persistenceLayer: PersistenceLayer, lookBackHours: Int): Int {
        val now = System.currentTimeMillis()
        val fromTime = now - lookBackHours * 60 * 60 * 1000L

        // Récupère les basales temporaires triées par timestamp décroissant
        val tempBasals: List<TB> = persistenceLayer
            .getTemporaryBasalsStartingFromTime(fromTime, ascending = false)
            .blockingGet()

        if (tempBasals.isEmpty()) {
            return 0 // Aucune donnée disponible pendant la période de recherche
        }

        var lastZeroTimestamp = fromTime // Initialiser avec le timestamp de base

        for (event in tempBasals) {
            if (event.rate > 0.05) break
            lastZeroTimestamp = event.timestamp
        }

        // Si aucun événement n'a un taux > 0.05, alors on considère la durée depuis le début de la période
        val zeroDuration = if (lastZeroTimestamp == fromTime) {
            now - fromTime
        } else {
            now - lastZeroTimestamp
        }

        return (zeroDuration / 60000L).toInt()
    }
    // -- Classe représentant la décision de sécurité --
    data class SafetyDecision(
        val stopBasal: Boolean,      // true => arrête la basale (ou force une basale à 0)
        val bolusFactor: Double,     // Facteur multiplicateur appliqué à la dose SMB (1.0 = dose complète, 0.0 = annulation)
        val reason: String,           // Log résumant les critères ayant conduit à la décision
        val basalLS: Boolean
    )

    // -- Calcul de la chute de BG par heure sur une fenêtre donnée (en minutes) --
    fun calculateDropPerHour(bgHistory: List<Float>, windowMinutes: Float): Float {
        if (bgHistory.size < 2) return 0f
        val first = bgHistory.first()  // plus ancien
        val last  = bgHistory.last()   // plus récent
        val drop  = (first - last)     // positif si baisse
        return drop * (60f / windowMinutes)
    }

    /**
     * Ajuste la dose d'insuline (SMB) et décide éventuellement de stopper la basale.
     *
     * @param currentBG Glycémie actuelle (mg/dL).
     * @param predictedBG Glycémie prédite par l'algorithme (mg/dL).
     * @param bgHistory Historique des BG récents (pour calculer le drop/h).
     * @param combinedDelta Delta combiné mesuré et prédit (mg/dL/5min).
     * @param iob Insuline active (IOB).
     * @param maxIob IOB maximum autorisé.
     * @param tdd24Hrs Total daily dose sur 24h (U).
     * @param tddPerHour TDD/h sur la dernière heure (U/h).
     * @param tirInhypo Pourcentage du temps passé en hypo.
     * @param targetBG Objectif de glycémie (mg/dL).
     * @param zeroBasalDurationMinutes Durée cumulée en minutes pendant laquelle la basale est déjà à zéro.
     */
    fun safetyAdjustment(
        currentBG: Float,
        predictedBG: Float,
        bgHistory: List<Float>,
        combinedDelta: Float,
        iob: Float,
        maxIob: Float,
        tdd24Hrs: Float,
        tddPerHour: Float,
        tirInhypo: Float,
        targetBG: Float,
        zeroBasalDurationMinutes: Int
    ): SafetyDecision {
        val windowMinutes = 30f
        val dropPerHour = calculateDropPerHour(bgHistory, windowMinutes)
        val maxAllowedDropPerHour = 25f  // Seuil de chute rapide à ajuster si besoin
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)

        val reasonBuilder = StringBuilder()
        var stopBasal = false
        var basalLS = false

        // Liste des facteurs multiplicatifs proposés ; on calculera la moyenne à la fin
        val factors = mutableListOf<Float>()

        // 1. Contrôle de la chute rapide
        if (dropPerHour >= maxAllowedDropPerHour) {
            stopBasal = true
            factors.add(0.3f)
            reasonBuilder.append("BG drop élevé ($dropPerHour mg/dL/h), forte réduction; ")
        }

        // 2. Mode montée très rapide : override de toutes les réductions
        if (delta >= 20f && combinedDelta >= 15f && !honeymoon) {
            // on passe outre toutes les réductions ; bolusFactor sera 1.0
            reasonBuilder.append("Montée rapide détectée (delta $delta mg/dL), application du mode d'urgence; ")
        } else {
            // 3. Ajustement selon combinedDelta
            when {
                combinedDelta < 1f -> {
                    factors.add(0.6f)
                    reasonBuilder.append("combinedDelta très faible ($combinedDelta), réduction x0.6; ")
                }
                combinedDelta < 2f -> {
                    factors.add(0.8f)
                    reasonBuilder.append("combinedDelta modéré ($combinedDelta), réduction x0.8; ")
                }
                else -> {
                    // Appel au multiplicateur lissé
                    factors.add(computeDynamicBolusMultiplier(combinedDelta))
                    reasonBuilder.append("combinedDelta élevé ($combinedDelta), multiplicateur dynamique appliqué; ")
                }
            }

            // 4. Plateau BG élevé + combinedDelta très faible
            if (currentBG > 160f && combinedDelta < 1f) {
                factors.add(0.8f)
                reasonBuilder.append("Plateau BG>160 & combinedDelta<1, réduction x0.8; ")
            }

            // 5. Contrôle IOB
            if (iob >= maxIob * 0.85f) {
                factors.add(0.85f)
                reasonBuilder.append("IOB élevé ($iob U), réduction x0.85; ")
            }

            // 6. Contrôle du TDD par heure
            val tddThreshold = tdd24Hrs / 24f
            if (tddPerHour > tddThreshold) {
                factors.add(0.8f)
                reasonBuilder.append("TDD/h élevé ($tddPerHour U/h), réduction x0.8; ")
            }

            // 7. TIR élevé
            if (tirInhypo >= 8f) {
                factors.add(0.5f)
                reasonBuilder.append("TIR élevé ($tirInhypo%), réduction x0.5; ")
            }

            // 8. BG prédit proche de la cible
            if (predictedBG < targetBG + 10) {
                factors.add(0.5f)
                reasonBuilder.append("BG prédit ($predictedBG) proche de la cible ($targetBG), réduction x0.5; ")
            }
        }

        // Calcul du bolusFactor : 1.0 si aucune réduction, sinon moyenne des facteurs collectés
        var bolusFactor = if (factors.isNotEmpty()) {
            factors.average().toFloat().toDouble()
        } else {
            1.0
        }

        // 9. Zéro basal prolongé : on force le bolusFactor à 1 et on désactive l'arrêt basale
        if (zeroBasalDurationMinutes >= MAX_ZERO_BASAL_DURATION) {
            stopBasal = false
            basalLS = true
            bolusFactor = 1.0
            reasonBuilder.append("Zero basal duration ($zeroBasalDurationMinutes min) dépassé, forçant basal minimal; ")
        }

        return SafetyDecision(
            stopBasal = stopBasal,
            bolusFactor = bolusFactor,
            reason = reasonBuilder.toString(),
            basalLS = basalLS
        )
    }

    // -- Fonction de sécurité qui combine plusieurs indicateurs pour ajuster la dose d'insuline --
    // fun safetyAdjustment(
    //     currentBG: Float,
    //     predictedBG: Float,
    //     bgHistory: List<Float>,
    //     combinedDelta: Float,
    //     iob: Float,
    //     maxIob: Float,
    //     tdd24Hrs: Float,
    //     tddPerHour: Float,
    //     tirInhypo: Float,
    //     targetBG: Float,
    //     zeroBasalDurationMinutes: Int  // Nouvel argument indiquant combien de minutes consécutives la basale est restée à 0
    // ): SafetyDecision {
    //     val windowMinutes = 30f
    //     val dropPerHour = calculateDropPerHour(bgHistory, windowMinutes)
    //     val maxAllowedDropPerHour = 25f  // Ajustez si besoin
    //     val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
    //     val reasonBuilder = StringBuilder()
    //     var stopBasal = false
    //     var basalLS = false
    //     var bolusFactor = 1.0
    //
    //     // 1. Contrôle de la chute
    //     if (dropPerHour >= maxAllowedDropPerHour) {
    //         // Option A : on arrête complètement la basale
    //         stopBasal = true
    //         // reasonBuilder.append("BG drop élevé: $dropPerHour mg/dL/h; ")
    //
    //         // Option B : on réduit fortement le bolusFactor sans stopper la basale
    //         bolusFactor *= 0.3
    //         reasonBuilder.append("BG drop élevé ($dropPerHour mg/dL/h), forte réduction du bolus; ")
    //     }
    //     if (delta >= 20f && combinedDelta >= 15f && !honeymoon) {
    //         // Mode "montée rapide" détecté, on override les réductions habituelles
    //         bolusFactor = 1.0
    //         reasonBuilder.append("Montée rapide détectée (delta ${delta} mg/dL), application du mode d'urgence; ")
    //     }
    //     // 2. Palier sur le combinedDelta
    //     when {
    //         combinedDelta < 1f -> {
    //             bolusFactor *= 0.6
    //             reasonBuilder.append("combinedDelta très faible ($combinedDelta), réduction x0.6; ")
    //         }
    //         combinedDelta < 2f -> {
    //             bolusFactor *= 0.8
    //             reasonBuilder.append("combinedDelta modéré ($combinedDelta), réduction x0.8; ")
    //         }
    //         else -> {
    //             bolusFactor *= computeDynamicBolusMultiplier(combinedDelta)
    //             reasonBuilder.append("combinedDelta élevé ($combinedDelta), pas de réduction; ")
    //         }
    //     }
    //
    //     // 3. Plateau si BG élevé + combinedDelta très faible
    //     if (currentBG > 160f && combinedDelta < 1f) {
    //         bolusFactor *= 0.8
    //         reasonBuilder.append("Plateau BG>180 & combinedDelta<2 => réduction x0.8; ")
    //     }
    //
    //     // 4. Contrôle IOB
    //     if (iob >= maxIob * 0.85f) {
    //         bolusFactor *= 0.85
    //         reasonBuilder.append("IOB élevé ($iob U), réduction x0.8; ")
    //     }
    //
    //     // 5. Contrôle du TDD par heure
    //     val tddThreshold = tdd24Hrs / 24f
    //     if (tddPerHour > tddThreshold) {
    //         bolusFactor *= 0.8
    //         reasonBuilder.append("TDD/h élevé ($tddPerHour U/h), réduction x0.8; ")
    //     }
    //
    //     // 6. TIR élevé
    //     if (tirInhypo >= 8f) {
    //         bolusFactor *= 0.5
    //         reasonBuilder.append("TIR élevé ($tirInhypo%), réduction x0.5; ")
    //     }
    //
    //     // 7. BG prédit proche de la cible
    //     if (predictedBG < targetBG + 10) {
    //         bolusFactor *= 0.5
    //         reasonBuilder.append("BG prédit ($predictedBG) proche de la cible ($targetBG), réduction x0.5; ")
    //     }
    //     // ---- Intégration du suivi de durée zéro basal ----
    //     // Si nous avons déjà trop longtemps de basal à 0, on ne souhaite pas stopper la basale.
    //     // Par exemple, si la durée cumulée dépasse 60 minutes, on force l'arrêt de la réduction (i.e. on ne stoppe pas la basale)
    //     if (zeroBasalDurationMinutes >= MAX_ZERO_BASAL_DURATION) {
    //         // On annule la demande de stopper la basale et on force le bolusFactor à 1 (aucune réduction)
    //         stopBasal = false
    //         basalLS = true
    //         bolusFactor = 1.0
    //         reasonBuilder.append("Zero basal duration ($zeroBasalDurationMinutes min) dépassé, forçant basal minimal; ")
    //     }
    //
    //     return SafetyDecision(
    //         stopBasal = stopBasal,
    //         bolusFactor = bolusFactor,
    //         reason = reasonBuilder.toString(),
    //         basalLS = basalLS
    //     )
    // }
    /**
     * Ajuste le DIA (en minutes) en fonction du niveau d'IOB.
     *
     * @param diaMinutes Le DIA courant (en minutes) après les autres ajustements.
     * @param currentIOB La quantité actuelle d'insuline active (U).
     * @param threshold Le seuil d'IOB à partir duquel on commence à augmenter le DIA (par défaut 7 U).
     * @return Le DIA ajusté en minutes tenant compte de l'impact de l'IOB.
     */
    fun adjustDIAForIOB(diaMinutes: Float, currentIOB: Float, threshold: Float = 2f): Float {
        // Si l'IOB est inférieur ou égal au seuil, pas d'ajustement.
        if (currentIOB <= threshold) return diaMinutes

        // Calculer l'excès d'IOB
        val excess = currentIOB - threshold
        // Pour chaque unité au-dessus du seuil, augmenter le DIA de 5 %.
        val multiplier = 1 + 0.05f * excess
        return diaMinutes * multiplier
    }
    /**
     * Calcule le DIA ajusté en minutes en fonction de plusieurs paramètres :
     * - baseDIAHours : le DIA de base en heures (par exemple, 9.0 pour 9 heures)
     * - currentHour : l'heure actuelle (0 à 23)
     * - recentSteps5Minutes : nombre de pas sur les 5 dernières minutes
     * - currentHR : fréquence cardiaque actuelle (bpm)
     * - averageHR60 : fréquence cardiaque moyenne sur les 60 dernières minutes (bpm)
     *
     * La logique appliquée :
     * 1. Conversion du DIA de base en minutes.
     * 2. Ajustement selon l'heure de la journée :
     *    - Matin (6-10h) : réduction de 20% (×0.8),
     *    - Soir/Nuit (22-23h et 0-5h) : augmentation de 20% (×1.2).
     * 3. Ajustement en fonction de l'activité physique :
     *    - Si recentSteps5Minutes > 200 et que currentHR > averageHR60, on réduit le DIA de 30% (×0.7).
     *    - Si recentSteps5Minutes == 0 et que currentHR > averageHR60, on augmente le DIA de 30% (×1.3).
     * 4. Ajustement selon la fréquence cardiaque absolue :
     *    - Si currentHR > 130 bpm, on réduit le DIA de 30% (×0.7).
     * 5. Le résultat final est contraint entre 180 minutes (3h) et 720 minutes (12h).
     */
    fun calculateAdjustedDIA(
        baseDIAHours: Float,
        currentHour: Int,
        recentSteps5Minutes: Int,
        currentHR: Float,
        averageHR60: Float,
        pumpAgeDays: Float,
        iob: Double = 0.0 // Ajout du paramètre IOB
    ): Double {
        val reasonBuilder = StringBuilder()

        // 1. Conversion du DIA de base en minutes
        var diaMinutes = baseDIAHours * 60f  // Pour 9h, 9*60 = 540 min
        reasonBuilder.append("Base DIA: ${baseDIAHours}h = ${diaMinutes}min\n")

        // 2. Ajustement selon l'heure de la journée
        // Matin (6-10h) : absorption plus rapide, réduction du DIA de 20%
        if (currentHour in 6..10) {
            diaMinutes *= 0.8f
            reasonBuilder.append("Morning adjustment (6-10h): reduced by 20%\n")
        }
        // Soir/Nuit (22-23h et 0-5h) : absorption plus lente, augmentation du DIA de 20%
        else if (currentHour in 22..23 || currentHour in 0..5) {
            diaMinutes *= 1.2f
            reasonBuilder.append("Night adjustment (22-23h & 0-5h): increased by 20%\n")
        }

        // 3. Ajustement en fonction de l'activité physique
        if (recentSteps5Minutes > 200 && currentHR > averageHR60) {
            // Exercice : absorption accélérée, réduction du DIA de 30%
            diaMinutes *= 0.7f
            reasonBuilder.append("Physical activity detected: reduced by 30%\n")
        } else if (recentSteps5Minutes == 0 && currentHR > averageHR60) {
            // Aucune activité mais HR élevée (stress) : absorption potentiellement plus lente, augmentation du DIA de 30%
            diaMinutes *= 1.3f
            reasonBuilder.append("High HR without activity (stress): increased by 30%\n")
        }

        // 4. Ajustement en fonction du niveau absolu de fréquence cardiaque
        if (currentHR > 130f) {
            // HR très élevée : circulation rapide, réduction du DIA de 30%
            diaMinutes *= 0.7f
            reasonBuilder.append("High HR (>130bpm): reduced by 30%\n")
        }

        // 5. Ajustement en fonction de l'IOB (Insulin on Board)
        // Si le patient a déjà beaucoup d'insuline active, il faut réduire le DIA pour éviter l'hypoglycémie
        diaMinutes = adjustDIAForIOB(diaMinutes, iob.toFloat())
        // if (iob > 2.0) {
        //     diaMinutes *= 0.8f
        //     reasonBuilder.append("High IOB (${iob}U): reduced by 20%\n")
        // } else if (iob < 0.5) {
        //     diaMinutes *= 1.1f
        //     reasonBuilder.append("Low IOB (${iob}U): increased by 10%\n")
        // }

        // 6. Ajustement en fonction de l'âge du site d'insuline
        // Si le site est utilisé depuis 2 jours ou plus, augmenter le DIA de 10% par jour supplémentaire.
        if (pumpAgeDays >= 2f) {
            val extraDays = pumpAgeDays - 2f
            val ageMultiplier = 1 + 0.1f * extraDays  // 10% par jour supplémentaire
            diaMinutes *= ageMultiplier
            reasonBuilder.append("Pump age (${pumpAgeDays} days): increased by ${extraDays * 10}%\n")
        }

        // 7. Contrainte de la plage finale : entre 180 min (3h) et 720 min (12h)
        val finalDiaMinutes = diaMinutes.coerceIn(180f, 720f)
        reasonBuilder.append("Final DIA constrained to [180, 720] min: ${finalDiaMinutes}min")

        println("DIA Calculation Details:")
        println(reasonBuilder.toString())

        return finalDiaMinutes.toDouble()
    }

    // fun calculateAdjustedDIA(
    //     baseDIAHours: Float,
    //     currentHour: Int,
    //     recentSteps5Minutes: Int,
    //     currentHR: Float,
    //     averageHR60: Float,
    //     pumpAgeDays: Float
    // ): Double {
    //     val reasonBuilder = StringBuilder()
    //     // 1. Conversion du DIA de base en minutes
    //     var diaMinutes = baseDIAHours * 60f  // Pour 9h, 9*60 = 540 min
    //
    //     // 2. Ajustement selon l'heure de la journée
    //     if (currentHour in 6..10) {
    //         // Le matin : absorption plus rapide, on réduit le DIA de 20%
    //         diaMinutes *= 0.8f
    //     } else if (currentHour in 22..23 || currentHour in 0..5) {
    //         // Soir/Nuit : absorption plus lente, on augmente le DIA de 20%
    //         diaMinutes *= 1.2f
    //     }
    //
    //     // 3. Ajustement en fonction de l'activité physique
    //     if (recentSteps5Minutes > 200 && currentHR > averageHR60) {
    //         // Exercice : absorption accélérée, réduire le DIA de 30%
    //         diaMinutes *= 0.7f
    //     } else if (recentSteps5Minutes == 0 && currentHR > averageHR60) {
    //         // Aucune activité mais HR élevée (stress) : absorption potentiellement plus lente, augmenter le DIA de 30%
    //         diaMinutes *= 1.3f
    //     }
    //
    //     // 4. Ajustement en fonction du niveau absolu de fréquence cardiaque
    //     if (currentHR > 130f) {
    //         // HR très élevée : circulation rapide, réduire le DIA de 30%
    //         diaMinutes *= 0.7f
    //     }
    //
    //     // 5. Ajustement en fonction de l'IOB
    //     diaMinutes = adjustDIAForIOB(diaMinutes, iob)
    //     // Si le site est utilisé depuis 2 jours ou plus, augmenter le DIA de 10% par jour supplémentaire.
    //     if (pumpAgeDays >= 2f) {
    //         val extraDays = pumpAgeDays - 2f
    //         val ageMultiplier = 1 + 0.2f * extraDays  // par exemple, 2 jours => 1 + 0.2*1 = 1.2
    //         diaMinutes *= ageMultiplier
    //     }
    //
    //     // 6. Contrainte de la plage finale : entre 180 min (3h) et 720 min (12h)
    //     diaMinutes = diaMinutes.coerceIn(180f, 720f)
    //     reasonBuilder.append("Dia in minutes : $diaMinutes")
    //     return diaMinutes.toDouble()
    // }

    // -- Méthode pour obtenir l'historique récent de BG, similaire à getRecentBGs() --
    private fun getRecentBGs(): List<Float> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        if (data.isEmpty()) return emptyList()
        val intervalMinutes = if (bg < 130) 50f else 25f
        val nowTimestamp = data.first().timestamp
        val recentBGs = mutableListOf<Float>()

        for (i in 1 until data.size) {
            if (data[i].value > 39 && !data[i].filledGap) {
                val minutesAgo = ((nowTimestamp - data[i].timestamp) / (1000.0 * 60)).toFloat()
                if (minutesAgo in 1.0f..intervalMinutes) {
                    // Utilisation de la valeur recalculée comme BG
                    recentBGs.add(data[i].recalculated.toFloat())
                }
            }
        }
        return recentBGs
    }
fun appendCompactLog(
    reason: StringBuilder,
    peakTime: Double,
    bg: Double,
    delta: Float,
    stepCount: Int?,
    heartRate: Double?
) {
    val bgStr = "%.0f".format(bg)
    val deltaStr = "%.1f".format(delta)
    val peakStr = "%.1f".format(peakTime)

    reason.append("🕒 PeakTime=$peakStr min | BG=$bgStr Δ$deltaStr")
    stepCount?.let { reason.append(" | Steps=$it") }
    heartRate?.let { reason.append(" | HR=$it bpm") }
    reason.append("\n")
}
    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }

    private fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)
    fun round(value: Double): Int {
        if (value.isNaN()) return 0
        val scale = 10.0.pow(2.0)
        return (Math.round(value * scale) / scale).toInt()
    }
    private fun calculateRate(basal: Double, currentBasal: Double, multiplier: Double, reason: String, currenttemp: CurrentTemp, rT: RT): Double {
        rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} $reason")
        return if (basal == 0.0) currentBasal * multiplier else roundBasal(basal * multiplier)
    }
    private fun calculateBasalRate(basal: Double, currentBasal: Double, multiplier: Double): Double =
        if (basal == 0.0) currentBasal * multiplier else roundBasal(basal * multiplier)

    private fun convertBG(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")

    private fun enablesmb(profile: OapsProfileAimi, microBolusAllowed: Boolean, mealData: MealData, targetbg: Double): Boolean {
        // disable SMB when a high temptarget is set
        if (!microBolusAllowed) {
            consoleError.add("SMB disabled (!microBolusAllowed)")
            return false
        } else if (!profile.allowSMB_with_high_temptarget && profile.temptargetSet && targetbg > 100) {
            consoleError.add("SMB disabled due to high temptarget of $targetbg")
            return false
        }

        // enable SMB/UAM if always-on (unless previously disabled for high temptarget)
        if (profile.enableSMB_always) {
            consoleError.add("SMB enabled due to enableSMB_always")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) while we have COB
        if (profile.enableSMB_with_COB && mealData.mealCOB != 0.0) {
            consoleError.add("SMB enabled for COB of ${mealData.mealCOB}")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) for a full 6 hours after any carb entry
        // (6 hours is defined in carbWindow in lib/meal/total.js)
        if (profile.enableSMB_after_carbs && mealData.carbs != 0.0) {
            consoleError.add("SMB enabled for 6h after carb entry")
            return true
        }

        // enable SMB/UAM (if enabled in preferences) if a low temptarget is set
        if (profile.enableSMB_with_temptarget && (profile.temptargetSet && targetbg < 100)) {
            consoleError.add("SMB enabled for temptarget of ${convertBG(targetbg)}")
            return true
        }

        consoleError.add("SMB disabled (no enableSMB preferences active or no condition satisfied)")
        return false
    }

    fun reason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty()) rT.reason.append(". ")
        rT.reason.append(msg)
        consoleError.add(msg)
    }

    fun setTempBasal(
        _rate: Double,
        duration: Int,
        profile: OapsProfileAimi,
        rT: RT,
        currenttemp: CurrentTemp,
        overrideSafetyLimits: Boolean = false
    ): RT {
        // ────────────────────────────────────────────────────────────────
        // 0️⃣ LGS / Hypo kill-switch (avant tout)
        val lgsPref = profile.lgsThreshold
        val hypoGuard =  computeHypoThreshold(minBg = profile.min_bg, lgsThreshold = lgsPref)
        val bgNow = bg
        if (bgNow <= hypoGuard) {
            rT.reason.append("🛑 LGS: BG=${"%.0f".format(bgNow)} ≤ ${"%.0f".format(hypoGuard)} → TBR 0U/h (30m)\n")
            rT.duration = maxOf(duration, 30)
            rT.rate = 0.0
            return rT
        }
        // 1️⃣ Recalcule des modes
        val therapy = Therapy(persistenceLayer).also { it.updateStatesBasedOnTherapyEvents() }
        val isMealMode = therapy.snackTime || therapy.highCarbTime || therapy.mealTime
            || therapy.lunchTime || therapy.dinnerTime || therapy.bfastTime

        val reason = StringBuilder()

        // 2️⃣ Disponibilité BG
        val recentBGs = getRecentBGs()
        val hasBgData = (bgNow > 39.0) && recentBGs.isNotEmpty() // ne dépend plus uniquement d’un flag global

        // 3️⃣ Cas capteur / données insuffisantes
        if (!hasBgData) {
            val safeRate = if (bgNow <= hypoGuard) 0.0 else _rate.coerceIn(0.0, profile.max_basal)

            rT.reason.append("⚠️ Données BG insuffisantes ou invalides → fallback\n")
            rT.reason.append("Pose temp à ${"%.2f".format(safeRate)} U/h pour $duration minutes.\n")
            rT.duration = duration
            rT.rate = safeRate
            return rT
        }

        // 4️⃣ Early autodrive
        val hour = Calendar.getInstance()[Calendar.HOUR_OF_DAY]
        val night = hour <= 7
        val predDelta = predictedDelta(getRecentDeltas()).toFloat()
        val autodrive = preferences.get(BooleanKey.OApsAIMIautoDrive)

        val isEarlyAutodrive = !night && !isMealMode && autodrive && bgNow > 110 && detectMealOnset(
            delta, predDelta, bgacc.toFloat()
        )

        // 5️⃣ Ajustement sur tendance BG
        var rateAdjustment = _rate
        val bgTrend = calculateBgTrend(recentBGs, reason)
        rateAdjustment = adjustRateBasedOnBgTrend(_rate, bgTrend)

        // 6️⃣ Bypass sécurité
        val bypassSafety = (overrideSafetyLimits || isMealMode || isEarlyAutodrive) && bgNow > hypoGuard

        // 7️⃣ Max safe basale
        val maxSafe = min(
            profile.max_basal,
            min(
                profile.max_daily_safety_multiplier * profile.max_daily_basal,
                profile.current_basal_safety_multiplier * profile.current_basal
            )
        )

        // 8️⃣ Choix du rate effectif
        var rate = when {
            bgNow <= hypoGuard -> 0.0
            bypassSafety       -> rateAdjustment
            else               -> rateAdjustment.coerceIn(0.0, maxSafe)
        }
        // ♀️ Ajustement cycle sur la basale (si activé)
        if (bgNow > hypoGuard) { // on n'applique pas en LGS
            rate = applyWCycleOnBasal(rate, bypassSafety, maxSafe, profile, rT)
        }
        // 9️⃣ Logging
        when {
            bgNow <= hypoGuard -> rT.reason.append("🛑 LGS override → TBR 0U/h\n")
            bypassSafety       -> rT.reason.append("→ bypass sécurité${if (isMealMode) " (meal mode)" else if (isEarlyAutodrive) " (early autodrive)" else ""}\n")
            rate != _rate      -> rT.reason.append("→ rate adjusted based on BG trend\n")
        }

        // 🔟 Pose
        rT.reason.append("Pose temp à ${"%.2f".format(rate)} U/h pour $duration minutes.\n")
        rT.duration = duration
        rT.rate = rate
        return rT
    }

    private fun calculateBgTrend(recentBGs: List<Float>, reason: StringBuilder): Float {
    if (recentBGs.isEmpty()) {
        reason.append("✘ Aucun historique de glycémie disponible.\n")
        return 0.0f
    }

    // Hypothèse : recentBGs = liste du plus récent au plus ancien → on inverse
    val sortedBGs = recentBGs.reversed()

    val firstValue = sortedBGs.first()
    val lastValue = sortedBGs.last()
    val count = sortedBGs.size

    val bgTrend = (lastValue - firstValue) / count.toFloat()

    reason.append("→ Analyse BG Trend\n")
    reason.append("  • Première glycémie : $firstValue mg/dL\n")
    reason.append("  • Dernière glycémie : $lastValue mg/dL\n")
    reason.append("  • Nombre de valeurs : $count\n")
    reason.append("  • Tendance calculée : $bgTrend mg/dL/intervalle\n")

    return bgTrend
}

    private fun adjustRateBasedOnBgTrend(_rate: Double, bgTrend: Float): Double {
        // Si la BG est accessible dans le scope, on peut aussi y jeter un œil ici :
        val bgNow = bg
        // Si on s’approche du seuil hypo et que la tendance est négative, coupe à 0
        if (bgNow <=  (80.0 + 10.0) && bgTrend < 0f) return 0.0
        val adjustmentFactor = if (bgTrend < 0.0f) 0.8 else 1.2
        return _rate * adjustmentFactor
    }


    private fun logDataMLToCsv(predictedSMB: Float, smbToGive: Float) {
        val usFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).format(usFormatter)

        val headerRow = "dateStr, bg, iob, cob, delta, shortAvgDelta, longAvgDelta, tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour, predictedSMB, smbGiven\n"
        val valuesToRecord = "$dateStr," +
            "$bg,$iob,$cob,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$predictedSMB,$smbToGive"


        if (!csvfile.exists()) {
            csvfile.parentFile?.mkdirs()
            csvfile.createNewFile()
            csvfile.appendText(headerRow)
        }
        csvfile.appendText(valuesToRecord + "\n")
    }

    private fun logDataToCsv(predictedSMB: Float, smbToGive: Float) {

        val usFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).format(usFormatter)

        val headerRow = "dateStr,hourOfDay,weekend," +
            "bg,targetBg,iob,delta,shortAvgDelta,longAvgDelta," +
            "tdd7DaysPerHour,tdd2DaysPerHour,tddPerHour,tdd24HrsPerHour," +
            "recentSteps5Minutes,recentSteps10Minutes,recentSteps15Minutes,recentSteps30Minutes,recentSteps60Minutes,recentSteps180Minutes," +
            "tags0to60minAgo,tags60to120minAgo,tags120to180minAgo,tags180to240minAgo," +
            "predictedSMB,maxIob,maxSMB,smbGiven\n"
        val valuesToRecord = "$dateStr,$hourOfDay,$weekend," +
            "$bg,$targetBg,$iob,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes,$recentSteps180Minutes," +
            "$tags0to60minAgo,$tags60to120minAgo,$tags120to180minAgo,$tags180to240minAgo," +
            "$predictedSMB,$maxIob,$maxSMB,$smbToGive"
        if (!csvfile2.exists()) {
            csvfile2.parentFile?.mkdirs() // Crée le dossier s'il n'existe pas
            csvfile2.createNewFile()
            csvfile2.appendText(headerRow)
        }
        csvfile2.appendText(valuesToRecord + "\n")
    }

    fun removeLast200Lines(csvFile: File) {
        val reasonBuilder = StringBuilder()
        if (!csvFile.exists()) {
            println("Le fichier original n'existe pas.")
            return
        }

        // Lire toutes les lignes du fichier
        val lines = csvFile.readLines(Charsets.UTF_8)

        if (lines.size <= 200) {
            reasonBuilder.append("Le fichier contient moins ou égal à 200 lignes, aucune suppression effectuée.")
            return
        }

        // Conserver toutes les lignes sauf les 200 dernières
        val newLines = lines.dropLast(200)

        // Création d'un nom de sauvegarde avec timestamp
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        val backupFileName = "backup_$timestamp.csv"
        val backupFile = File(csvFile.parentFile, backupFileName)

        // Sauvegarder le fichier original
        csvFile.copyTo(backupFile, overwrite = true)

        // Réécrire le fichier original avec les lignes restantes
        csvFile.writeText(newLines.joinToString("\n"), Charsets.UTF_8)

        reasonBuilder.append("Les 200 dernières lignes ont été supprimées. Le fichier original a été sauvegardé sous '$backupFileName'.")
    }
    private fun automateDeletionIfBadDay(tir1DAYIR: Int) {
        val reasonBuilder = StringBuilder()
        // Vérifier si le TIR est inférieur à 85%
        if (tir1DAYIR < 85) {
            // Vérifier si l'heure actuelle est entre 00:05 et 00:10
            val currentTime = LocalTime.now()
            val start = LocalTime.of(0, 5)
            val end = LocalTime.of(0, 10)

            if (currentTime.isAfter(start) && currentTime.isBefore(end)) {
                // Calculer la date de la veille au format dd/MM/yyyy
                val yesterday = LocalDate.now().minusDays(1)
                val dateToRemove = yesterday.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

                // Appeler la méthode de suppression
                //createFilteredAndSortedCopy(csvfile,dateToRemove)
                removeLast200Lines(csvfile)
                reasonBuilder.append("Les données pour la date $dateToRemove ont été supprimées car TIR1DAIIR est inférieur à 85%.")
            } else {
                reasonBuilder.append("La suppression ne peut être exécutée qu'entre 00:05 et 00:10.")
            }
        } else {
            reasonBuilder.append("Aucune suppression nécessaire : tir1DAYIR est supérieur ou égal à 85%.")
        }
    }

    private fun applySafetyPrecautions(
        mealData: MealData,
        smbToGiveParam: Float,
        hypoThreshold: Double,
        reason: StringBuilder? = null
    ): Float {
        var smbToGive = smbToGiveParam

        val (isCrit, critMsg) = isCriticalSafetyCondition(mealData, hypoThreshold)
        if (isCrit) {
            reason?.appendLine("🛑 $critMsg → SMB=0")
            return 0f
        }

        if (isSportSafetyCondition()) {
            reason?.appendLine("🏃‍♂️ Safety sport → SMB=0")
            return 0f
        }
        // ♀️ Ajustement cycle sur SMB (Ovulation: -, Lutéale: +5%, etc.)
        smbToGive = applyWCycleOnSmb(smbToGive, reason)
        // Ajustements spécifiques
        val beforeAdj = smbToGive
        smbToGive = applySpecificAdjustments(smbToGive)
        if (smbToGive != beforeAdj) {
            reason?.appendLine("🎛️ Ajustements: ${"%.2f".format(beforeAdj)} → ${"%.2f".format(smbToGive)} U")
        }

        // Finalisation
        val beforeFinalize = smbToGive
        smbToGive = finalizeSmbToGive(smbToGive)
        if (smbToGive != beforeFinalize) {
            reason?.appendLine("🧩 Finalisation: ${"%.2f".format(beforeFinalize)} → ${"%.2f".format(smbToGive)} U")
        }

        // Limites max
        val beforeLimits = smbToGive
        smbToGive = applyMaxLimits(smbToGive)
        if (smbToGive != beforeLimits) {
            reason?.appendLine("🧱 Limites: ${"%.2f".format(beforeLimits)} → ${"%.2f".format(smbToGive)} U")
        }
        smbToGive = smbToGive.coerceAtLeast(0f)
        return smbToGive
    }
    private fun applyMaxLimits(smbToGive: Float): Float {
        var result = smbToGive

        // Vérifiez d'abord si smbToGive dépasse maxSMB
        if (result > maxSMB) {
            result = maxSMB.toFloat()
        }
        // Ensuite, vérifiez si la somme de iob et smbToGive dépasse maxIob
        if (iob + result > maxIob) {
            result = maxIob.toFloat() - iob
        }

        return result
    }
    private fun hasReceivedPbolusMInLastHour(pbolusA: Double): Boolean {
        val epsilon = 0.01
        val oneHourAgo = dateUtil.now() - T.hours(1).msecs()

        val bolusesLastHour = persistenceLayer
            .getBolusesFromTime(oneHourAgo, true)
            .blockingGet()

        return bolusesLastHour.any { Math.abs(it.amount - pbolusA) < epsilon }
    }

    private fun isAutodriveModeCondition(
        delta: Float,
        autodrive: Boolean,
        slopeFromMinDeviation: Double,
        bg: Float,
        predictedBg: Float,
        reason: StringBuilder // ← on utilise CE builder-là
    ): Boolean {
        // ⚙️ Prefs
        val pbolusA: Double = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
        val autodriveDelta: Float = preferences.get(DoubleKey.OApsAIMIcombinedDelta).toFloat()
        val autodriveMinDeviation: Double = preferences.get(DoubleKey.OApsAIMIAutodriveDeviation)
        val autodriveBG: Int = preferences.get(IntKey.OApsAIMIAutodriveBG)

        // 📈 Deltas récents & delta combiné
        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas).toFloat()
        val combinedDelta = (delta + predicted) / 2f

        // 🔍 Tendance BG
        val recentBGs = getRecentBGs()
        var autodriveCondition = true
        if (recentBGs.isNotEmpty()) {
            val bgTrend = calculateBgTrend(recentBGs, reason)
            reason.appendLine(
                "📈 BGTrend=${"%.2f".format(bgTrend)} | Δcomb=${"%.2f".format(combinedDelta)} | predBG=${"%.0f".format(predictedBg)}"
            )
            autodriveCondition = adjustAutodriveCondition(bgTrend, predictedBg, combinedDelta, reason)
        } else {
            reason.appendLine("⚠️ Aucune BG récente — conditions par défaut conservées")
        }

        // ⛔ Ne pas relancer si pbolus récent
        if (hasReceivedPbolusMInLastHour(pbolusA)) {
            reason.appendLine("⛔ Pbolus ${"%.2f".format(pbolusA)}U < 60 min → autodrive=OFF")
            return false
        }

        // ✅ Décision finale
        val ok =
            autodriveCondition &&
                combinedDelta >= autodriveDelta &&
                autodrive &&
                predictedBg > 140 &&
                slopeFromMinDeviation >= autodriveMinDeviation &&
                bg >= autodriveBG.toFloat()

        reason.appendLine(
            "🚗 Autodrive: ${if (ok) "✅ ON" else "❌ OFF"} | " +
                "cond=$autodriveCondition, Δc≥${"%.2f".format(autodriveDelta)}, " +
                "predBG>140, slope≥${"%.2f".format(autodriveMinDeviation)}, bg≥${autodriveBG}"
        )

        return ok
    }

    private fun adjustAutodriveCondition(
        bgTrend: Float,
        predictedBg: Float,
        combinedDelta: Float,
        reason: StringBuilder
    ): Boolean {
        val autodriveDelta: Double = preferences.get(DoubleKey.OApsAIMIcombinedDelta)

        reason.append("→ Autodrive Debug\n")
        reason.append("  • BG Trend: $bgTrend\n")
        reason.append("  • Predicted BG: $predictedBg\n")
        reason.append("  • Combined Delta: $combinedDelta\n")
        reason.append("  • Required Combined Delta: $autodriveDelta\n")

        // Cas 1 : glycémie baisse => désactivation
        if (bgTrend < -0.15f) {
            reason.append("  ✘ Autodrive désactivé : tendance glycémie en baisse\n")
            return false
        }

        // Cas 2 : glycémie monte ou conditions fortes
        if ((bgTrend >= 0f && combinedDelta >= autodriveDelta) || (predictedBg > 140 && combinedDelta >= autodriveDelta)) {
            reason.append("  ✔ Autodrive activé : conditions favorables\n")
            return true
        }

        // Cas 3 : conditions non remplies
        reason.append("  ✘ Autodrive désactivé : conditions insuffisantes\n")
        return false
    }


    private fun isMealModeCondition(): Boolean {
        val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
        return mealruntime in 0..7 && lastBolusSMBUnit != pbolusM.toFloat() && mealTime
    }
    private fun isbfastModeCondition(): Boolean {
        val pbolusbfast: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
        return bfastruntime in 0..7 && lastBolusSMBUnit != pbolusbfast.toFloat() && bfastTime
    }
    private fun isbfast2ModeCondition(): Boolean {
        val pbolusbfast2: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
        return bfastruntime in 15..30 && lastBolusSMBUnit != pbolusbfast2.toFloat() && bfastTime
    }
    private fun isLunchModeCondition(): Boolean {
        val pbolusLunch: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
        return lunchruntime in 0..7 && lastBolusSMBUnit != pbolusLunch.toFloat() && lunchTime
    }
    private fun isLunch2ModeCondition(): Boolean {
        val pbolusLunch2: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
        return lunchruntime in 15..24 && lastBolusSMBUnit != pbolusLunch2.toFloat() && lunchTime
    }
    private fun isDinnerModeCondition(): Boolean {
        val pbolusDinner: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
        return dinnerruntime in 0..7 && lastBolusSMBUnit != pbolusDinner.toFloat() && dinnerTime
    }
    private fun isDinner2ModeCondition(): Boolean {
        val pbolusDinner2: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
        return dinnerruntime in 15..24 && lastBolusSMBUnit != pbolusDinner2.toFloat() && dinnerTime
    }
    private fun isHighCarbModeCondition(): Boolean {
        val pbolusHC: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
        return highCarbrunTime in 0..7 && lastBolusSMBUnit != pbolusHC.toFloat() && highCarbTime
    }

    private fun issnackModeCondition(): Boolean {
        val pbolussnack: Double = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
        return snackrunTime in 0..7 && lastBolusSMBUnit != pbolussnack.toFloat() && snackTime
    }
    private fun roundToPoint05(number: Float): Float {
        return (number * 20.0).roundToInt() / 20.0f
    }

    private fun isCriticalSafetyCondition(mealData: MealData,  hypoThreshold: Double): Pair<Boolean, String> {
        val cobFromMeal = try {
            // Adapte le nom selon ta classe (souvent mealData.cob ou mealData.mealCOB)
            mealData.mealCOB
        } catch (_: Throwable) {
            cob // variable globale déjà existante
        }.toDouble()
        // Extraction des données de contexte pour éviter les variables globales
        val context = SafetyContext(
            delta = delta.toDouble(),
            bg = bg,
            iob = iob.toDouble(),
            predictedBg = predictedBg.toDouble(),
            eventualBG = eventualBG,
            shortAvgDelta = shortAvgDelta.toDouble(),
            longAvgDelta = longAvgDelta.toDouble(),
            lastsmbtime = lastsmbtime,
            fastingTime = fastingTime,
            iscalibration = iscalibration,
            targetBg = targetBg.toDouble(),
            maxSMB = maxSMB,
            maxIob = maxIob,
            mealTime = mealTime,
            bfastTime = bfastTime,
            lunchTime = lunchTime,
            dinnerTime = dinnerTime,
            highCarbTime = highCarbTime,
            snackTime = snackTime,
            cob = cobFromMeal,
            hypoThreshold = hypoThreshold
        )

        // Récupération des conditions critiques
        val criticalConditions = determineCriticalConditions(context)

        // Calcul du résultat final
        val isCritical = criticalConditions.isNotEmpty()

        // Construction du message de retour
        val message = buildConditionMessage(isCritical, criticalConditions)

        return isCritical to message
    }

    /**
     * Structure de données pour le contexte de sécurité
     */
    private data class SafetyContext(
        val delta: Double,
        val bg: Double,
        val iob: Double,
        val predictedBg: Double,
        val eventualBG: Double,
        val shortAvgDelta: Double,
        val longAvgDelta: Double,
        val lastsmbtime: Int,
        val fastingTime: Boolean,
        val iscalibration: Boolean,
        val targetBg: Double,
        val maxSMB: Double,
        val maxIob: Double,
        val mealTime: Boolean,
        val bfastTime: Boolean,
        val lunchTime: Boolean,
        val dinnerTime: Boolean,
        val highCarbTime: Boolean,
        val snackTime: Boolean,
        val cob: Double,
        val hypoThreshold: Double
    )
    private fun isHypoBlocked(context: SafetyContext): Boolean =
        shouldBlockHypoWithHysteresis(
            bg = context.bg,
            predictedBg = context.predictedBg,
            eventualBg = context.eventualBG,
            threshold = context.hypoThreshold,
            deltaMgdlPer5min = context.delta
        )
    /**
     * Détermine les conditions critiques à partir du contexte fourni
     */
    private fun determineCriticalConditions(context: SafetyContext): List<String> {
        val conditions = mutableListOf<String>()

        // Vérification des conditions critiques avec des noms explicites
        if (isHypoBlocked(context)) conditions.add("hypoGuard")
        if (isNosmbHm(context)) conditions.add("nosmbHM")
        if (isHoneysmb(context)) conditions.add("honeysmb")
        if (isNegDelta(context)) conditions.add("negdelta")
        if (isNosmb(context)) conditions.add("nosmb")
        if (isFasting(context)) conditions.add("fasting")
        if (isBelowMinThreshold(context)) conditions.add("belowMinThreshold")
        if (isNewCalibration(context)) conditions.add("isNewCalibration")
        if (isBelowTargetAndDropping(context)) conditions.add("belowTargetAndDropping")
        if (isBelowTargetAndStableButNoCob(context)) conditions.add("belowTargetAndStableButNoCob")
        if (isDroppingFast(context)) conditions.add("droppingFast")
        if (isDroppingFastAtHigh(context)) conditions.add("droppingFastAtHigh")
        if (isDroppingVeryFast(context)) conditions.add("droppingVeryFast")
        if (isPrediction(context)) conditions.add("prediction")
        if (isBg90(context)) conditions.add("bg90")
        if (isAcceleratingDown(context)) conditions.add("acceleratingDown")

        return conditions
    }

    /**
     * Construction du message de retour décrivant les conditions remplies
     */
    private fun buildConditionMessage(isCritical: Boolean, conditions: List<String>): String {
        val conditionsString = if (conditions.isNotEmpty()) {
            conditions.joinToString(", ")
        } else {
            "No conditions met"
        }

        return "Safety condition $isCritical : $conditionsString"
    }

    // Fonctions de vérification spécifiques pour chaque condition
    private fun isNosmbHm(context: SafetyContext): Boolean =
        context.iob > 0.7 &&
            preferences.get(BooleanKey.OApsAIMIhoneymoon) &&
            context.delta <= 10.0 &&
            !context.mealTime &&
            !context.bfastTime &&
            !context.lunchTime &&
            !context.dinnerTime &&
            context.predictedBg < 130

    private fun isHoneysmb(context: SafetyContext): Boolean =
        preferences.get(BooleanKey.OApsAIMIhoneymoon) &&
            context.delta < 0 &&
            context.bg < 170

    private fun isNegDelta(context: SafetyContext): Boolean =
        context.delta <= -1 &&
            !context.mealTime &&
            !context.bfastTime &&
            !context.lunchTime &&
            !context.dinnerTime &&
            context.eventualBG < 120

    private fun isNosmb(context: SafetyContext): Boolean =
        context.iob >= 2 * context.maxSMB &&
            context.bg < 110 &&
            context.delta < 10 &&
            !context.mealTime &&
            !context.bfastTime &&
            !context.lunchTime &&
            !context.dinnerTime

    private fun isFasting(context: SafetyContext): Boolean = context.fastingTime

    private fun isBelowMinThreshold(context: SafetyContext): Boolean =
        context.bg < 60 // Seuil arbitraire pour la valeur minimale

    private fun isNewCalibration(context: SafetyContext): Boolean = context.iscalibration

    private fun isBelowTargetAndDropping(context: SafetyContext): Boolean =
        context.bg < context.targetBg &&
            context.delta < 0

    private fun isBelowTargetAndStableButNoCob(context: SafetyContext): Boolean =
        context.bg < context.targetBg &&
            context.delta >= 0 &&
            context.cob <= 0 // Pas de COB (Carbohydrate On Board)

    private fun isDroppingFast(context: SafetyContext): Boolean =
        context.delta < -2.0 // Seuil arbitraire pour une chute rapide

    private fun isDroppingFastAtHigh(context: SafetyContext): Boolean =
        context.bg > 180 &&
            context.delta < -1.5

    private fun isDroppingVeryFast(context: SafetyContext): Boolean =
        context.delta < -3.0

    private fun isPrediction(context: SafetyContext): Boolean =
        context.predictedBg < context.bg &&
            context.delta < 0

    private fun isBg90(context: SafetyContext): Boolean = context.bg < 90

    private fun isAcceleratingDown(context: SafetyContext): Boolean =
        context.delta < 0 &&
            context.longAvgDelta < 0 &&
            context.shortAvgDelta < 0

    private fun isSportSafetyCondition(): Boolean {
        val sport = targetBg >= 140 && recentSteps5Minutes >= 200 && recentSteps10Minutes >= 400
        val sport1 = targetBg >= 140 && recentSteps5Minutes >= 200 && averageBeatsPerMinute > averageBeatsPerMinute10
        val sport2 = recentSteps5Minutes >= 200 && averageBeatsPerMinute > averageBeatsPerMinute10
        val sport3 = recentSteps5Minutes >= 200 && recentSteps10Minutes >= 500
        val sport4 = targetBg >= 140
        val sport5= sportTime

        return sport || sport1 || sport2 || sport3 || sport4 || sport5

    }
    private fun calculateSMBInterval(): Int {
        val reasonBuilder = StringBuilder()

        // Récupération préalable des intervalles depuis les préférences
        val intervals = SMBIntervals(
            snack = preferences.get(IntKey.OApsAIMISnackinterval),
            meal = preferences.get(IntKey.OApsAIMImealinterval),
            bfast = preferences.get(IntKey.OApsAIMIBFinterval),
            lunch = preferences.get(IntKey.OApsAIMILunchinterval),
            dinner = preferences.get(IntKey.OApsAIMIDinnerinterval),
            sleep = preferences.get(IntKey.OApsAIMISleepinterval),
            hc = preferences.get(IntKey.OApsAIMIHCinterval),
            highBG = preferences.get(IntKey.OApsAIMIHighBGinterval)
        )

        // Condition critique : si delta > 15, intervalle fixe à 1
        if (delta > 15f) {
            reasonBuilder.append("Interval : 1 (delta > 15)")
            return 1
        }

        var interval = 5 // Intervalle de base

        // Vérification des ajustements basés sur les intervalles configurés
        if (shouldApplyIntervalAdjustment(intervals)) {
            interval = 0
        } else if (shouldApplySafetyAdjustment()) {
            interval = 10
        } else if (shouldApplyTimeAdjustment()) {
            interval = 10
        }

        // Ajustement basé sur l'activité physique
        if (shouldApplyStepAdjustment()) {
            interval = 0
        }

        // Ajustements supplémentaires :
        if (bg < targetBg) {
            interval = (interval * 2).coerceAtMost(20)
        }

        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        if (honeymoon && bg < 170 && delta < 5) {
            interval = (interval * 2).coerceAtMost(20)
        }

        val currentHour = LocalTime.now().hour
        if (preferences.get(BooleanKey.OApsAIMInight) && currentHour == 23 && delta < 10 && iob < maxSMB) {
            interval = (interval * 0.8).toInt()
        }

        reasonBuilder.append("Interval : $interval")
        return interval
    }

    // Structure pour regrouper les intervalles
    data class SMBIntervals(
        val snack: Int,
        val meal: Int,
        val bfast: Int,
        val lunch: Int,
        val dinner: Int,
        val sleep: Int,
        val hc: Int,
        val highBG: Int
    )

    // Refacto des fonctions de vérification conditionnelles
    private fun shouldApplyIntervalAdjustment(intervals: SMBIntervals): Boolean {
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)

        return (lastsmbtime < intervals.snack && snackTime)
            || (lastsmbtime < intervals.meal && mealTime)
            || (lastsmbtime < intervals.bfast && bfastTime)
            || (lastsmbtime < intervals.lunch && lunchTime)
            || (lastsmbtime < intervals.dinner && dinnerTime)
            || (lastsmbtime < intervals.sleep && sleepTime)
            || (lastsmbtime < intervals.hc && highCarbTime)
            || (!honeymoon && lastsmbtime < intervals.highBG && bg > 120)
            || (honeymoon && lastsmbtime < intervals.highBG && bg > 180)
    }

    private fun shouldApplySafetyAdjustment(): Boolean {
        val safetysmb = recentSteps180Minutes > 1500 && bg < 120
        return (safetysmb || lowCarbTime) && lastsmbtime >= 15
    }

    private fun shouldApplyTimeAdjustment(): Boolean {
        val safetysmb = recentSteps180Minutes > 1500 && bg < 120
        return (safetysmb || lowCarbTime) && lastsmbtime < 15
    }

    private fun shouldApplyStepAdjustment(): Boolean {
        return recentSteps5Minutes > 100 && recentSteps30Minutes > 500 && lastsmbtime < 20
    }
    // Calcule le seuil "OpenAPS-like" et applique LGS si plus haut
    private fun computeHypoThreshold(minBg: Double, lgsThreshold: Int?): Double {
        var t = minBg - 0.5 * (minBg - 40.0) // 90→65, 100→70, 110→75, 130→85
        if (lgsThreshold != null && lgsThreshold > t) t = lgsThreshold.toDouble()
        return t
    }

    // Hard safety: vrai si BG, predictedBG ou eventualBG passent sous le seuil
    private fun isBelowHypoThreshold(
        bg: Double,
        predictedBg: Double,
        eventualBg: Double,
        threshold: Double,
        deltaMgdlPer5min: Double = 0.0
    ): Boolean {
        fun safe(v: Double) = if (v.isFinite()) v else Double.POSITIVE_INFINITY
        val minBg = minOf(safe(bg), safe(predictedBg), safe(eventualBg))

        val extraMargin = when {
            minBg <= threshold           -> 0.0
            deltaMgdlPer5min <= -3.0     -> HYPO_MARGIN_FAST
            deltaMgdlPer5min <= -1.5     -> HYPO_MARGIN_FALL
            else                         -> HYPO_MARGIN_BASE
        }
        return minBg <= threshold + extraMargin
    }
    // Hystérèse : on ne débloque qu’après avoir été > (seuil+margin) pendant X minutes
    private fun shouldBlockHypoWithHysteresis(
        bg: Double,
        predictedBg: Double,
        eventualBg: Double,
        threshold: Double,
        deltaMgdlPer5min: Double,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        fun safe(v: Double) = if (v.isFinite()) v else Double.POSITIVE_INFINITY
        val minBg = minOf(safe(bg), safe(predictedBg), safe(eventualBg))

        val blockedNow = isBelowHypoThreshold(bg, predictedBg, eventualBg, threshold, deltaMgdlPer5min)
        if (blockedNow) {
            lastHypoBlockAt = now
            hypoClearCandidateSince = null
            return true
        }

        // jamais bloqué avant → pas de collant
        if (lastHypoBlockAt == 0L) return false

        val above = minBg > threshold + HYPO_RELEASE_MARGIN
        if (above) {
            if (hypoClearCandidateSince == null) hypoClearCandidateSince = now
            val heldMs = now - hypoClearCandidateSince!!
            return if (heldMs >= HYPO_RELEASE_HOLD_MIN * 60_000L) {
                // libération de l’hystérèse
                lastHypoBlockAt = 0L
                hypoClearCandidateSince = null
                false
            } else {
                true // on colle encore
            }
        } else {
            // rechute sous (seuil+margin) → on réinitialise la fenêtre de libération
            hypoClearCandidateSince = null
            return true
        }
    }

    private fun applySpecificAdjustments(smbAmount: Float): Float {
        val intervals = SMBIntervals(
            snack  = preferences.get(IntKey.OApsAIMISnackinterval),
            meal   = preferences.get(IntKey.OApsAIMImealinterval),
            bfast  = preferences.get(IntKey.OApsAIMIBFinterval),
            lunch  = preferences.get(IntKey.OApsAIMILunchinterval),
            dinner = preferences.get(IntKey.OApsAIMIDinnerinterval),
            sleep  = preferences.get(IntKey.OApsAIMISleepinterval),
            hc     = preferences.get(IntKey.OApsAIMIHCinterval),
            highBG = preferences.get(IntKey.OApsAIMIHighBGinterval)
        )

        val currentHour = LocalTime.now().hour
        val honeymoon   = preferences.get(BooleanKey.OApsAIMIhoneymoon)

        // 1) hard/tempo guards existants
        when {
            shouldApplyIntervalAdjustment(intervals) -> return 0.0f
            shouldApplySafetyAdjustment() -> {
                this.intervalsmb = 10
                return smbAmount / 2
            }
            shouldApplyTimeAdjustment() -> {
                this.intervalsmb = 10
                return 0.0f
            }
        }

        if (shouldApplyStepAdjustment()) return 0.0f

        // 2) 🔧 AJUSTEMENT “falling decelerating” (soft)
        //    On baisse encore (deltas négatifs) mais la baisse RALENTIT :
        //    shortAvgDelta est moins négatif que longAvgDelta → on temporise.
        val fallingDecelerating =
            delta < -EPS_FALL &&
                shortAvgDelta < -EPS_FALL &&
                longAvgDelta  < -EPS_FALL &&
                shortAvgDelta >  longAvgDelta + EPS_ACC

        if (fallingDecelerating && bg < targetBg + 10) {
            // On est sous/près de la cible et la baisse ralentit → on réduit le SMB
            return (smbAmount * 0.5f).coerceAtLeast(0f)
        }

        // 3) règles existantes “soft”
        val belowTarget = bg < targetBg
        if (belowTarget) return smbAmount / 2

        if (honeymoon && bg < 170 && delta < 5) return smbAmount / 2

        if (preferences.get(BooleanKey.OApsAIMInight) && currentHour == 23 && delta < 10 && iob < maxSMB) {
            return smbAmount * 0.8f
        }
        if (currentHour in 0..7 && delta < 10 && iob < maxSMB) {
            return smbAmount * 0.8f
        }

        return smbAmount
    }

    private fun finalizeSmbToGive(smbToGive: Float): Float {
        var result = smbToGive
        // Assurez-vous que smbToGive n'est pas négatif
        if (result < 0.0f) {
            result = 0.0f
        }
        if (iob <= 0.1 && bg > 120 && delta >= 2 && result == 0.0f) {
            result = 0.1f
        }
        return result
    }

    // DetermineBasalAIMI2.kt
    private fun calculateSMBFromModel(reason: StringBuilder? = null): Float {
        val smb = AimiUamHandler.predictSmbUam(
            floatArrayOf(
                hourOfDay.toFloat(), weekend.toFloat(),
                bg.toFloat(), targetBg, iob,
                delta, shortAvgDelta, longAvgDelta,
                tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour,
                recentSteps5Minutes.toFloat(), recentSteps10Minutes.toFloat(),
                recentSteps15Minutes.toFloat(), recentSteps30Minutes.toFloat(),
                recentSteps60Minutes.toFloat(), recentSteps180Minutes.toFloat()
            ),
            reason // 👈 logs visibles si non-null
        )
        return smb.coerceAtLeast(0f)
    }



    // private fun calculateSMBFromModel(): Float {
    //     val selectedModelFile: File?
    //     val modelInputs: FloatArray
    //
    //     when {
    //         cob > 0 && lastCarbAgeMin < 240 && modelFile.exists() -> {
    //             selectedModelFile = modelFile
    //             modelInputs = floatArrayOf(
    //                 hourOfDay.toFloat(), weekend.toFloat(),
    //                 bg.toFloat(), targetBg, iob, cob, lastCarbAgeMin.toFloat(), futureCarbs, delta, shortAvgDelta, longAvgDelta
    //             )
    //         }
    //
    //         modelFileUAM.exists()   -> {
    //             selectedModelFile = modelFileUAM
    //             modelInputs = floatArrayOf(
    //                 hourOfDay.toFloat(), weekend.toFloat(),
    //                 bg.toFloat(), targetBg, iob, delta, shortAvgDelta, longAvgDelta,
    //                 tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour,
    //                 recentSteps5Minutes.toFloat(),recentSteps10Minutes.toFloat(),recentSteps15Minutes.toFloat(),recentSteps30Minutes.toFloat(),recentSteps60Minutes.toFloat(),recentSteps180Minutes.toFloat()
    //             )
    //         }
    //
    //         else                 -> {
    //             return 0.0F
    //         }
    //     }
    //
    //     val interpreter = Interpreter(selectedModelFile)
    //     val output = arrayOf(floatArrayOf(0.0F))
    //     interpreter.run(modelInputs, output)
    //     interpreter.close()
    //     var smbToGive = output[0][0].toString().replace(',', '.').toDouble()
    //
    //     val formatter = DecimalFormat("#.####", DecimalFormatSymbols(Locale.US))
    //     smbToGive = formatter.format(smbToGive).toDouble()
    //
    //     return smbToGive.toFloat()
    // }

    private fun neuralnetwork5(
    delta: Float,
    shortAvgDelta: Float,
    longAvgDelta: Float,
    predictedSMB: Float,
    profile: OapsProfileAimi
): Float {
    val recentDeltas = getRecentDeltas()
    val predicted = predictedDelta(recentDeltas)
    val combinedDelta = (delta + predicted) / 2.0f
    // Définir un nombre maximal d'itérations plus bas en cas de montée rapide
    val maxIterations = if (combinedDelta > 15f) 25 else 50
    var finalRefinedSMB: Float = calculateSMBFromModel()

    val allLines = csvfile.readLines()
    println("CSV file path: \${csvfile.absolutePath}")
    if (allLines.isEmpty()) {
        println("CSV file is empty.")
        return predictedSMB
    }

    val headerLine = allLines.first()
    val headers = headerLine.split(",").map { it.trim() }
    val requiredColumns = listOf(
        "bg", "iob", "cob", "delta", "shortAvgDelta", "longAvgDelta",
        "tdd7DaysPerHour", "tdd2DaysPerHour", "tddPerHour", "tdd24HrsPerHour",
        "predictedSMB", "smbGiven"
    )
    if (!requiredColumns.all { headers.contains(it) }) {
        println("CSV file is missing required columns.")
        return predictedSMB
    }

    val colIndices = requiredColumns.map { headers.indexOf(it) }
    val targetColIndex = headers.indexOf("smbGiven")
    val inputs = mutableListOf<FloatArray>()
    val targets = mutableListOf<DoubleArray>()
    var lastEnhancedInput: FloatArray? = null

    for (line in allLines.drop(1)) {
        val cols = line.split(",").map { it.trim() }
        val rawInput = colIndices.mapNotNull { idx -> cols.getOrNull(idx)?.toFloatOrNull() }.toFloatArray()

        val trendIndicator = calculateTrendIndicator(
            delta, shortAvgDelta, longAvgDelta,
            bg.toFloat(), iob, variableSensitivity, cob, normalBgThreshold,
            recentSteps180Minutes, averageBeatsPerMinute.toFloat(), averageBeatsPerMinute10.toFloat(),
            profile.insulinDivisor.toFloat(), recentSteps5Minutes, recentSteps10Minutes
        )

        val enhancedInput = rawInput.copyOf(rawInput.size + 1)
        enhancedInput[rawInput.size] = trendIndicator.toFloat()
        lastEnhancedInput = enhancedInput

        val targetValue = cols.getOrNull(targetColIndex)?.toDoubleOrNull()
        if (targetValue != null) {
            inputs.add(enhancedInput)
            targets.add(doubleArrayOf(targetValue))
        }
    }

    if (inputs.isEmpty() || targets.isEmpty()) {
        println("Insufficient data for training.")
        return predictedSMB
    }

    val maxK = 10
    val adjustedK = minOf(maxK, inputs.size)
    val foldSize = maxOf(1, inputs.size / adjustedK)
    var bestNetwork: AimiNeuralNetwork? = null
    var bestFoldValLoss = Double.MAX_VALUE

    for (k in 0 until adjustedK) {
        val validationInputs = inputs.subList(k * foldSize, minOf((k + 1) * foldSize, inputs.size))
        val validationTargets = targets.subList(k * foldSize, minOf((k + 1) * foldSize, targets.size))
        val trainingInputs = inputs.minus(validationInputs)
        val trainingTargets = targets.minus(validationTargets)
        if (validationInputs.isEmpty()) continue

        val tempNetwork = AimiNeuralNetwork(
            inputSize = inputs.first().size,
            hiddenSize = 5,
            outputSize = 1,
            config = TrainingConfig(
                learningRate = 0.001,
                epochs = 200
            ),
            regularizationLambda = 0.01
        )

        tempNetwork.trainWithValidation(trainingInputs, trainingTargets, validationInputs, validationTargets)
        val foldValLoss = tempNetwork.validate(validationInputs, validationTargets)

        if (foldValLoss < bestFoldValLoss) {
            bestFoldValLoss = foldValLoss
            bestNetwork = tempNetwork
        }
    }

    val adjustedLearningRate = if (bestFoldValLoss < 0.01) 0.0005 else 0.001
    val epochs = if (bestFoldValLoss < 0.01) 100 else 200

    if (bestNetwork != null) {
        println("Réentraînement final avec les meilleurs hyperparamètres sur toutes les données...")
        val finalNetwork = AimiNeuralNetwork(
            inputSize = inputs.first().size,
            hiddenSize = 5,
            outputSize = 1,
            config = TrainingConfig(
                learningRate = adjustedLearningRate,
                beta1 = 0.9,
                beta2 = 0.999,
                epsilon = 1e-8,
                patience = 10,
                batchSize = 32,
                weightDecay = 0.01,
                epochs = epochs,
                useBatchNorm = false,
                useDropout = true,
                dropoutRate = 0.3,
                leakyReluAlpha = 0.01
            ),
            regularizationLambda = 0.01
        )
        finalNetwork.copyWeightsFrom(bestNetwork)
        finalNetwork.trainWithValidation(inputs, targets, inputs, targets)
        bestNetwork = finalNetwork
    }

    // --- Normalisation légère sur lastEnhancedInput ---
    fun normalize(input: FloatArray): FloatArray {
        val mean = input.average().toFloat()
        val std = input.map { (it - mean) * (it - mean) }.average().let { sqrt(it).toFloat().coerceAtLeast(1e-8f) }
        return input.map { (it - mean) / std }.toFloatArray()
    }

    var iterationCount = 0
    do {
        val dynamicThreshold = calculateDynamicThreshold(iterationCount, delta, shortAvgDelta, longAvgDelta)
        val normalizedInput = lastEnhancedInput?.let { normalize(it) }?.toDoubleArray() ?: DoubleArray(0)
        val refinedSMB = bestNetwork?.let {
            AimiNeuralNetwork.refineSMB(finalRefinedSMB, it, normalizedInput)
        } ?: finalRefinedSMB

        println("→ Iteration $iterationCount | SMB=$finalRefinedSMB → $refinedSMB | Δ=${abs(finalRefinedSMB - refinedSMB)} | threshold=$dynamicThreshold")

        if (abs(finalRefinedSMB - refinedSMB) <= dynamicThreshold) {
            finalRefinedSMB = max(0.05f, refinedSMB)
            break
        }
        iterationCount++
    } while (iterationCount < maxIterations)

    if (finalRefinedSMB > predictedSMB && bg > 150 && delta > 5) {
        println("Modèle prédictif plus élevé, ajustement retenu.")
        return finalRefinedSMB
    }

    val alpha = 0.7f
    val blendedSMB = alpha * finalRefinedSMB + (1 - alpha) * predictedSMB
    return blendedSMB
}
    // private fun computeDynamicBolusMultiplier(delta: Float): Float {
    //     return when {
    //         delta > 20f -> 1.2f  // Montée très rapide : augmenter la dose corrective
    //         delta > 15f -> 1.1f  // Montée rapide
    //         delta > 10f -> 1.0f  // Montée modérée : pas de réduction
    //         delta in 5f..10f -> 0.9f // Légère réduction pour des changements moins brusques
    //         else -> 0.8f // Pour des variations faibles ou des baisses, appliquer une réduction standard
    //     }
    // }
    private fun computeDynamicBolusMultiplier(delta: Float): Float {
        // Centrer la sigmoïde autour de 5 mg/dL, avec une pente modérée (échelle 10)
        val x = (delta - 5f) / 10f
        val sig = (1f / (1f + exp(-x)))  // sigmoïde entre 0 et 1
        return 0.5f + sig * 0.7f  // multipliateur lissé entre 0,5 et 1,2
    }

    private fun calculateDynamicThreshold(
        iterationCount: Int,
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float
    ): Float {
        val baseThreshold = if (delta > 15f) 1.5f else 2.5f
        // Réduit le seuil au fur et à mesure des itérations pour exiger une convergence plus fine
        val iterationFactor = 1.0f / (1 + iterationCount / 100)
        val trendFactor = when {
            delta > 8 || shortAvgDelta > 4 || longAvgDelta > 3 -> 0.5f
            delta < 5 && shortAvgDelta < 3 && longAvgDelta < 3 -> 1.5f
            else -> 1.0f
        }
        return baseThreshold * iterationFactor * trendFactor
    }

    private fun FloatArray.toDoubleArray(): DoubleArray {
        return this.map { it.toDouble() }.toDoubleArray()
    }

    private fun calculateGFactor(delta: Float, lastHourTIRabove120: Double, bg: Float): Double {
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        // 🔹 Facteurs initiaux (ajustés dynamiquement)
        var deltaFactor = when {
            bg > 140 && delta > 5 -> 2.0 // Réaction forte si la glycémie monte rapidement et est élevée
            bg > 120 && delta > 2 -> 1.5 // Réaction modérée
            delta > 1  -> 1.2 // Réaction légère
            bg < 120 && delta < 0 -> 0.6
            else -> 1.0 // Pas de variation significative
        }
        var bgFactor = when {
            bg > 140 -> 1.8 // Réduction forte si glycémie > 150 mg/dL
            bg > 120 -> 1.4 // Réduction modérée si > 120 mg/dL
            bg > 100 -> 1.0 // Neutre entre 100 et 120
            bg < 80  -> 0.5 // Augmente l'ISF si la glycémie est sous la cible
            else -> 0.9 // Légère augmentation de l'ISF
        }
        var tirFactor = when {
            lastHourTIRabove120 > 0.5 && bg > 120 -> 1.2 + lastHourTIRabove120 * 0.15 // Augmente si tendance à rester haut
            bg < 100 -> 0.8 // Augmente l'ISF si retour à une glycémie basse
            else -> 1.0
        }
        // 🔹 Mode "Honeymoon" (ajustements spécifiques)
        if (honeymoon) {
            deltaFactor = when {
                bg > 140 && delta > 5 -> 2.2
                bg > 120 && delta > 2 -> 1.7
                delta > 1 -> 1.3
                else -> 1.0
            }
            bgFactor = when {
                bg > 150 -> 1.6
                bg > 130 -> 1.4
                bg < 100 -> 0.6 // Augmente encore plus l'ISF en honeymoon
                else -> 0.9
            }
            tirFactor = when {
                lastHourTIRabove120 > 0.5 && bg > 120 -> 1.3 + lastHourTIRabove120 * 0.05
                bg < 100 -> 0.7 // Encore plus de renforcement de l'ISF
                else -> 1.0
            }
        }

        // 🔹 Combine tous les facteurs
        return deltaFactor * bgFactor * tirFactor
    }
    private fun interpolateFactor(value: Float, start1: Float, end1: Float, start2: Float, end2: Float): Float {
        return start2 + (value - start1) * (end2 - start2) / (end1 - start1)
    }
    // // Méthode pour récupérer les deltas récents (entre 2.5 et 7.5 minutes par exemple)
    // private fun getRecentDeltas(): List<Double> {
    //     val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
    //     if (data.isEmpty()) return emptyList()
    //     // Fenêtre standard selon BG
    //     val standardWindow = if (bg < 130) 40f else 20f
    //     // Fenêtre raccourcie pour détection rapide
    //     val rapidRiseWindow = 10f
    //     // Si le delta instantané est supérieur à 15 mg/dL, on choisit la fenêtre rapide
    //     val intervalMinutes = if (delta > 15) rapidRiseWindow else standardWindow
    //
    //     val nowTimestamp = data.first().timestamp
    //     val recentDeltas = mutableListOf<Double>()
    //     for (i in 1 until data.size) {
    //         if (data[i].value > 39 && !data[i].filledGap) {
    //             val minutesAgo = ((nowTimestamp - data[i].timestamp) / (1000.0 * 60)).toFloat()
    //             if (minutesAgo in 0.0f..intervalMinutes) {
    //                 val delta = (data.first().recalculated - data[i].recalculated) / minutesAgo * 5f
    //                 recentDeltas.add(delta)
    //             }
    //         }
    //     }
    //     return recentDeltas
    // }
    private fun getRecentDeltas(): List<Double> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        if (data.isEmpty()) return emptyList()

        // Fenêtre standard selon BG
        val standardWindow = if (bg < 130) 40f else 20f
        // Fenêtre raccourcie pour détection rapide
        val rapidRiseWindow = 10f
        // Si le delta instantané est supérieur à 15 mg/dL, on choisit la fenêtre rapide
        val intervalMinutes = if (delta > 15) rapidRiseWindow else standardWindow

        val nowTimestamp = data.first().timestamp
        return data.drop(1).filter { it.value > 39 && !it.filledGap }
            .mapNotNull { entry ->
                val minutesAgo = ((nowTimestamp - entry.timestamp) / (1000.0 * 60)).toFloat()
                if (minutesAgo in 0.0f..intervalMinutes) {
                    val delta = (data.first().recalculated - entry.recalculated) / minutesAgo * 5f
                    delta
                } else {
                    null
                }
            }
    }


    // Calcul d'un delta prédit à partir d'une moyenne pondérée
    private fun predictedDelta(deltaHistory: List<Double>): Double {
        if (deltaHistory.isEmpty()) return 0.0
        // Par exemple, on peut utiliser une moyenne pondérée avec des poids croissants pour donner plus d'importance aux valeurs récentes
        val weights = (1..deltaHistory.size).map { it.toDouble() }
        val weightedSum = deltaHistory.zip(weights).sumOf { it.first * it.second }
        return weightedSum / weights.sum()
    }

    private fun adjustFactorsBasedOnBgAndHypo(
        morningFactor: Float,
        afternoonFactor: Float,
        eveningFactor: Float
    ): Triple<Float, Float, Float> {
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        val hypoAdjustment = if (bg < 120 || (iob > 3 * maxSMB)) 0.3f else 0.9f
        // Récupération des deltas récents et calcul du delta prédit
        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas)
        // Calcul du delta combiné : combine le delta mesuré et le delta prédit
        val combinedDelta = (delta + predicted) / 2.0f
        // s'assurer que combinedDelta est positif pour le calcul logarithmique
        val safeCombinedDelta = if (combinedDelta <= 0) 0.0001f else combinedDelta
        val deltaAdjustment = ln(safeCombinedDelta.toDouble() + 1).coerceAtLeast(0.0)


        // Interpolation de base pour factorAdjustment selon la glycémie (bg)
        var factorAdjustment = when {
            bg < 110 -> interpolateFactor(bg.toFloat(), 70f, 110f, 0.1f, 0.3f)
            else -> interpolateFactor(bg.toFloat(), 110f, 280f, 0.75f, 2.5f)
        }
        if (honeymoon) factorAdjustment = when {
            bg < 160 -> interpolateFactor(bg.toFloat(), 70f, 160f, 0.2f, 0.4f)
            else -> interpolateFactor(bg.toFloat(), 160f, 250f, 0.4f, 0.65f)
        }
        var bgAdjustment = 1.0f + (deltaAdjustment - 1) * factorAdjustment
        bgAdjustment *= 1.2f

        val dynamicCorrection = when {
            hourOfDay in 0..11 || hourOfDay in 15..19 || hourOfDay >= 22 -> 0.7f
            combinedDelta > 11f  -> 2.5f   // Très forte montée, on augmente très agressivement
            combinedDelta > 8f  -> 2.0f   // Montée forte
            combinedDelta > 4f  -> 1.5f   // Montée modérée à forte
            combinedDelta > 2f  -> 1.0f   // Montée légère
            combinedDelta in -2f..2f -> 0.8f  // Stable
            combinedDelta < -2f && combinedDelta >= -4f -> 0.7f  // Baisse légère
            combinedDelta < -4f && combinedDelta >= -6f -> 0.5f  // Baisse modérée
            combinedDelta < -6f -> 0.4f   // Baisse forte, on diminue considérablement pour éviter l'hypo
            else -> 1.0f
        }
        // On applique ce facteur sur bgAdjustment pour intégrer l'anticipation
        bgAdjustment *= dynamicCorrection

        // // Interpolation pour scalingFactor basée sur la cible (targetBg)
        // val scalingFactor = interpolateFactor(bg.toFloat(), targetBg, 110f, 09f, 0.5f).coerceAtLeast(0.1f)

        val maxIncreaseFactor = 12.5f
        val maxDecreaseFactor = 0.2f

        val adjustFactor = { factor: Float ->
            val adjustedFactor = factor * bgAdjustment * hypoAdjustment //* scalingFactor
            adjustedFactor.coerceIn(((factor * maxDecreaseFactor).toDouble()), ((factor * maxIncreaseFactor).toDouble()))
        }

        return Triple(
            adjustFactor(morningFactor).takeIf { !it.isNaN() } ?: morningFactor,
            adjustFactor(afternoonFactor).takeIf { !it.isNaN() } ?: afternoonFactor,
            adjustFactor(eveningFactor).takeIf { !it.isNaN() } ?: eveningFactor
        ) as Triple<Float, Float, Float>
    }



    private fun calculateAdjustedDelayFactor(
        bg: Float,
        recentSteps180Minutes: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float
    ): Float {
        val currentHour = LocalTime.now().hour
        var delayFactor = if (bg.isNaN() || averageBeatsPerMinute.isNaN() || averageBeatsPerMinute10.isNaN() || averageBeatsPerMinute10 == 0f) {
            1f
        } else {
            val stepActivityThreshold = 1500
            val heartRateIncreaseThreshold = 1.2
            val insulinSensitivityDecreaseThreshold = 1.5 * normalBgThreshold

            val increasedPhysicalActivity = recentSteps180Minutes > stepActivityThreshold
            val heartRateChange = averageBeatsPerMinute / averageBeatsPerMinute10
            val increasedHeartRateActivity = heartRateChange >= heartRateIncreaseThreshold

            val baseFactor = when {
                bg <= normalBgThreshold -> 1f
                bg <= insulinSensitivityDecreaseThreshold -> 1f - ((bg - normalBgThreshold) / (insulinSensitivityDecreaseThreshold - normalBgThreshold))
                else -> 0.5f
            }

            if (increasedPhysicalActivity || increasedHeartRateActivity) {
                (baseFactor.toFloat() * 0.8f).coerceAtLeast(0.5f)
            } else {
                baseFactor.toFloat()
            }
        }
        // Augmenter le délai si l'heure est le soir (18h à 23h) ou diminuer le besoin entre 00h à 5h
        if (currentHour in 18..23) {
            delayFactor *= 1.2f
        } else if (currentHour in 0..5) {
            delayFactor *= 0.8f
        }
        return delayFactor
    }

    fun calculateMinutesAboveThreshold(
        bg: Double,           // Glycémie actuelle (mg/dL)
        slope: Double,        // Pente de la glycémie (mg/dL par minute)
        thresholdBG: Double   // Seuil de glycémie (mg/dL)
    ): Int {
        val bgDifference = bg - thresholdBG

        // Vérifier si la glycémie est en baisse
        if (slope >= 0) {
            // La glycémie est stable ou en hausse, retournez une valeur élevée
            return Int.MAX_VALUE // ou un grand nombre, par exemple 999
        }

        // Estimer le temps jusqu'au seuil
        val minutesAboveThreshold = bgDifference / -slope

        // Vérifier que le temps est positif et raisonnable
        return if (minutesAboveThreshold.isFinite() && minutesAboveThreshold > 0) {
            minutesAboveThreshold.roundToInt()
        } else {
            // Retourner une valeur maximale par défaut si le calcul n'est pas valide
            Int.MAX_VALUE
        }
    }


    fun estimateRequiredCarbs(
        bg: Double, // Glycémie actuelle
        targetBG: Double, // Objectif de glycémie
        slope: Double, // Vitesse de variation de la glycémie (mg/dL par minute)
        iob: Double, // Insulin On Board - quantité d'insuline encore active
        csf: Double, // Facteur de sensibilité aux glucides (mg/dL par gramme de glucides)
        isf: Double, // Facteur de sensibilité à l'insuline (mg/dL par unité d'insuline)
        cob: Double // Carbs On Board - glucides en cours d'absorption
    ): Int {
        // 1. Calculer la projection de la glycémie future basée sur la pente actuelle et le temps (30 minutes)
        val timeAhead = 20.0 // Projection sur 30 minutes
        val projectedDrop = slope * timeAhead // Estimation de la chute future de la glycémie

        // 2. Estimer l'effet de l'insuline active restante (IOB) sur la glycémie
        val insulinEffect = iob * isf // L'effet de l'insuline résiduelle

        // 3. Effet total estimé : baisse de la glycémie + effet de l'insuline
        val totalPredictedDrop = projectedDrop + insulinEffect

        // 4. Calculer la glycémie future estimée sans intervention
        val futureBG = bg - totalPredictedDrop

        // 5. Si la glycémie projetée est inférieure à la cible, estimer les glucides nécessaires
        if (futureBG < targetBG) {
            val bgDifference = targetBG - futureBG

            // 6. Si des glucides sont en cours d'absorption (COB), les prendre en compte
            val netCarbImpact = max(0.0, bgDifference - (cob * csf)) // Ajuster avec COB

            // 7. Calculer les glucides nécessaires pour combler la différence de glycémie
            val carbsReq = round(netCarbImpact / csf)

            // Debug info
            consoleError.add("Future BG: $futureBG, Projected Drop: $projectedDrop, Insulin Effect: $insulinEffect, COB Impact: ${cob * csf}, Carbs Required: $carbsReq")

            return carbsReq
        }

        return 0 // Aucun glucide nécessaire si la glycémie future est au-dessus de la cible
    }

    private fun calculateInsulinEffect(
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        cob: Float,
        normalBgThreshold: Float,
        recentSteps180Min: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float,
        insulinDivisor: Float
    ): Float {
        val reasonBuilder = StringBuilder()
        // Calculer l'effet initial de l'insuline
        var insulinEffect = iob * variableSensitivity / insulinDivisor

        // Si des glucides sont présents, nous pourrions vouloir ajuster l'effet de l'insuline pour tenir compte de l'absorption des glucides.
        if (cob > 0) {
            // Ajustement hypothétique basé sur la présence de glucides. Ce facteur doit être déterminé par des tests/logique métier.
            insulinEffect *= 0.9f
        }
        val physicalActivityFactor = 1.0f - recentSteps180Min / 10000f
        insulinEffect *= physicalActivityFactor
        // Calculer le facteur de retard ajusté en fonction de l'activité physique
        val adjustedDelayFactor = calculateAdjustedDelayFactor(
            bg,
            recentSteps180Minutes,
            averageBeatsPerMinute,
            averageBeatsPerMinute10
        )

        // Appliquer le facteur de retard ajusté à l'effet de l'insuline
        insulinEffect *= adjustedDelayFactor
        if (bg > normalBgThreshold) {
            insulinEffect *= 1.2f
        }
        val currentHour = LocalTime.now().hour
        if (currentHour in 0..5) {
            insulinEffect *= 0.8f
        }
        reasonBuilder.append("insulin effect : $insulinEffect")
        return insulinEffect
    }
    private fun calculateTrendIndicator(
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float,
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        cob: Float,
        normalBgThreshold: Float,
        recentSteps180Min: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float,
        insulinDivisor: Float,
        recentSteps5min: Int,
        recentSteps10min: Int
    ): Int {

        // Calcul de l'impact de l'insuline
        val insulinEffect = calculateInsulinEffect(
            bg, iob, variableSensitivity, cob, normalBgThreshold, recentSteps180Min,
            averageBeatsPerMinute, averageBeatsPerMinute10, insulinDivisor
        )

        // Calcul de l'impact de l'activité physique
        val activityImpact = (recentSteps5min - recentSteps10min) * 0.05

        // Calcul de l'indicateur de tendance
        val trendValue = (delta * 0.5) + (shortAvgDelta * 0.25) + (longAvgDelta * 0.15) + (insulinEffect * 0.2) + (activityImpact * 0.1)

        return when {
            trendValue > 1.0 -> 1 // Forte tendance à la hausse
            trendValue < -1.0 -> -1 // Forte tendance à la baisse
            abs(trendValue) < 0.5 -> 0 // Pas de tendance significative
            trendValue > 0.5 -> 2 // Faible tendance à la hausse
            else -> -2 // Faible tendance à la baisse
        }
    }

    private fun predictEventualBG(
        bg: Float,                     // Glycémie actuelle (mg/dL)
        iob: Float,                    // Insuline active (IOB)
        variableSensitivity: Float,    // Sensibilité insulinique (mg/dL/U)
        minDelta: Float,               // Delta instantané (mg/dL/5min)
        minAvgDelta: Float,            // Delta moyen court terme (mg/dL/5min)
        longAvgDelta: Float,           // Delta moyen long terme (mg/dL/5min)
        mealTime: Boolean,
        bfastTime: Boolean,
        lunchTime: Boolean,
        dinnerTime: Boolean,
        highCarbTime: Boolean,
        snackTime: Boolean,
        honeymoon: Boolean
    ): Float {
        val reasonBuilder = StringBuilder()
        // 1. Détermination des paramètres glucidiques en fonction du contexte
        val (averageCarbAbsorptionTime, carbTypeFactor, estimatedCob) = when {
            highCarbTime -> Triple(3.5f, 0.75f, 100f)
            snackTime    -> Triple(1.5f, 1.25f, 15f)
            mealTime     -> Triple(2.5f, 1.0f, 55f)
            bfastTime    -> Triple(3.5f, 1.0f, 55f)
            lunchTime    -> Triple(2.5f, 1.0f, 70f)
            dinnerTime   -> Triple(2.5f, 1.0f, 70f)
            else         -> Triple(2.5f, 1.0f, 70f)
        }

        // 2. Détermination du facteur d'absorption en fonction de l'heure de la journée
        val currentHour = LocalTime.now().hour
        val absorptionFactor = when (currentHour) {
            in 6..10 -> 1.3f
            in 11..15 -> 0.8f
            in 16..23 -> 1.2f
            else -> 1.0f
        }

        // 3. Calcul de l'effet insuline
        val insulinEffect = iob * variableSensitivity

        // 4. Calcul de la déviation basée sur la tendance sur 30 minutes
        var deviation = (30f / 5f) * minDelta
        deviation *= absorptionFactor * carbTypeFactor
        if (deviation < 0) {
            deviation = (30f / 5f) * minAvgDelta * absorptionFactor * carbTypeFactor
            if (deviation < 0) {
                deviation = (30f / 5f) * longAvgDelta * absorptionFactor * carbTypeFactor
            }
        }

        // 5. Prédiction alternative basée sur un modèle dédié qui prend aussi le contexte alimentaire
        val predictedFutureBG = predictFutureBg(
            bg, iob, variableSensitivity,
            averageCarbAbsorptionTime, carbTypeFactor, estimatedCob,
            honeymoon
        )

        // 6. Combinaison finale : effet insuline + tendance + impact COB
        val predictedBG = predictedFutureBG + deviation + (estimatedCob * 0.05f)

        // 7. Seuil minimal de sécurité
        val finalPredictedBG = when {
            !honeymoon && predictedBG < 39f -> 39f
            honeymoon && predictedBG < 50f -> 50f
            else -> predictedBG
        }
        reasonBuilder.append("Predicted BG : $finalPredictedBG")
        return finalPredictedBG
    }

    private fun predictFutureBg(
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        averageCarbAbsorptionTime: Float,
        carbTypeFactor: Float,
        estimatedCob: Float,
        honeymoon: Boolean
    ): Float {
        // Prise en compte de l'effet insuline
        val insulinEffect = iob * variableSensitivity

        // Prise en compte d'une absorption glucidique estimée (simple modèle linéaire)
        val carbImpact = (estimatedCob / averageCarbAbsorptionTime) * carbTypeFactor

        var futureBg = bg - insulinEffect + carbImpact

        if (!honeymoon && futureBg < 39f) {
            futureBg = 39f
        } else if (honeymoon && futureBg < 50f) {
            futureBg = 50f
        }

        return futureBg
    }

    private fun interpolatebasal(bg: Double): Double {
        val clampedBG = bg.coerceIn(80.0, 300.0)
        return when {
            clampedBG < 80 -> 0.5
            clampedBG < 120 -> {
                // Interpolation linéaire entre 80 (0.5) et 120 (2.0)
                val slope = (2.0 - 0.5) / (120.0 - 80.0)  // 1.5/40 = 0.0375
                0.5 + slope * (clampedBG - 80.0)
            }
            clampedBG < 180 -> {
                // Interpolation linéaire entre 120 (2.0) et 180 (5.0)
                val slope = (5.0 - 2.0) / (180.0 - 120.0)  // 3.0/60 = 0.05
                2.0 + slope * (clampedBG - 120.0)
            }
            else -> 5.0
        }
    }

    private fun interpolate(xdata: Double): Double {
        // Définir les points de référence pour l'interpolation, à partir de 80 mg/dL
        val polyX = arrayOf(80.0, 90.0, 100.0, 110.0, 130.0, 160.0, 200.0, 220.0, 240.0, 260.0, 280.0, 300.0)
        val polyY = arrayOf(0.5, 1.0, 2.0, 3.0, 5.0, 7.0, 9.0, 10.0, 10.0, 10.0, 10.0, 10.0) // Ajustement des valeurs pour la basale

        // Constants for basal adjustment weights
        val higherBasalRangeWeight = 1.5 // Facteur pour les glycémies supérieures à 100 mg/dL
        val lowerBasalRangeWeight = 0.8 // Facteur pour les glycémies inférieures à 100 mg/dL mais supérieures ou égales à 80

        val polymax = polyX.size - 1
        var step = polyX[0]
        var sVal = polyY[0]
        var stepT = polyX[polymax]
        var sValold = polyY[polymax]

        var newVal = 1.0
        var lowVal = 1.0
        val topVal: Double
        val lowX: Double
        val topX: Double
        val myX: Double
        var lowLabl = step

        // État d'hypoglycémie (pour les valeurs < 80)
        if (xdata < 80) {
            newVal = 0.5 // Multiplicateur fixe pour l'hypoglycémie
        }
        // Extrapolation en avant (pour les valeurs > 300)
        else if (stepT < xdata) {
            step = polyX[polymax - 1]
            sVal = polyY[polymax - 1]
            lowVal = sVal
            topVal = sValold
            lowX = step
            topX = stepT
            myX = xdata
            newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
            // Limiter la valeur maximale si nécessaire
            newVal = min(newVal, 10.0) // Limitation de l'effet maximum à 10
        }
        // Interpolation normale
        else {
            for (i in 0..polymax) {
                step = polyX[i]
                sVal = polyY[i]
                if (step == xdata) {
                    newVal = sVal
                    break
                } else if (step > xdata) {
                    topVal = sVal
                    lowX = lowLabl
                    myX = xdata
                    topX = step
                    newVal = lowVal + (topVal - lowVal) / (topX - lowX) * (myX - lowX)
                    break
                }
                lowVal = sVal
                lowLabl = step
            }
        }

        // Appliquer des pondérations supplémentaires si nécessaire
        newVal = if (xdata > 100) {
            newVal * higherBasalRangeWeight
        } else {
            newVal * lowerBasalRangeWeight
        }

        // Limiter la valeur minimale à 0 et la valeur maximale à 10
        newVal = newVal.coerceIn(0.0, 10.0)

        return newVal
    }
    private fun calculateSmoothBasalRate(
        tddRecent: Float,
        tddPrevious: Float,
        currentBasalRate: Float
    ): Float {
        val weightRecent = 0.6f
        val weightPrevious = 1.0f - weightRecent  // 0.4f
        val weightedTdd = (tddRecent * weightRecent) + (tddPrevious * weightPrevious)
        val adjustedBasalRate = currentBasalRate * (weightedTdd / tddRecent)
        // Clamp pour éviter des ajustements trop extrêmes (par exemple, entre 50% et 200% de la basale actuelle)
        return adjustedBasalRate.coerceIn(currentBasalRate * 0.5f, currentBasalRate * 2.0f)
    }
    private fun computeFinalBasal(
        bg: Double,
        tddRecent: Float,
        tddPrevious: Float,
        currentBasalRate: Float
    ): Double {
        // 1. Lissage de la basale de fond sur la base du TDD
        val smoothBasal = calculateSmoothBasalRate(tddRecent, tddPrevious, currentBasalRate)

        // 2. Calcul du facteur d'ajustement en fonction de la glycémie
        val basalAdjustmentFactor = interpolate(bg)

        // 3. Application du facteur sur la basale lissée pour obtenir le taux final
        val finalBasal = smoothBasal * basalAdjustmentFactor

        // 4. Clamp du résultat final pour éviter des valeurs trop extrêmes (ex. entre 0 et 8 U/h)
        return finalBasal.coerceIn(0.0, 8.0)
    }

    private fun determineNoteBasedOnBg(bg: Double): String {
        return when {
            bg > 170 -> "more aggressive"
            bg in 90.0..100.0 -> "less aggressive"
            bg in 80.0..89.9 -> "too aggressive" // Vous pouvez ajuster ces valeurs selon votre logique
            bg < 80 -> "low treatment"
            else -> "normal" // Vous pouvez définir un autre message par défaut pour les cas non couverts
        }
    }
    private fun processNotesAndCleanUp(notes: String): String {
        return notes.lowercase()
            .replace(",", " ")
            .replace(".", " ")
            .replace("!", " ")
            //.replace("a", " ")
            .replace("an", " ")
            .replace("and", " ")
            .replace("\\s+", " ")
    }
    /** Log cycle : affiche dans reason et dans le consoleLog (colon). */
    private fun logWCycle(reason: StringBuilder?, msg: String) {
        reason?.append(msg)
        consoleLog.add(msg.replace("\n", "")) // colon : on évite les retours à la ligne
    }

    /** Format commun basique pour les multiplicateurs afin de ne pas spammer. */
    private fun fmtMul(tag: String, mul: Double): String =
        "$tag×${"%.2f".format(mul)}"

    // --- Cycle féminin : phases et multiplicateurs ---
    private enum class CyclePhase { MENSTRUATION, FOLLICULAR, OVULATION, LUTEAL, UNKNOWN }

    private data class WCycleInfo(
        val enabled: Boolean,
        val dayInCycle: Int,                 // 0..27
        val phase: CyclePhase,
        val basalMultiplier: Double,         // multiplicateur pour TBR
        val smbMultiplier: Double,           // multiplicateur pour SMB
        val log: String
    )

    /**
     * Calcule la phase courante sur un cycle fixe de 28 jours à partir du "jour du mois"
     * saisi par l'utilisatrice (ex: 18 = 18 du mois courant). Gère le changement de mois.
     *
     * Les % sont lus dans Preferences :
     *  - OApsAIMIwcyclemenstruation : -5 à -10 % (basal)
     *  - OApsAIMIwcycleovulation    : -2 à -3 % (SMB)
     *  - OApsAIMIwcycleluteal       : +8 à +15 % (basal)
     *
     * Recommandation tableau : en phase lutéale on ajoute aussi +5% au bolus → SMB ×1.05.
     */
    private fun computeCurrentWCycleInfo(nowDate: LocalDate = LocalDate.now()): WCycleInfo {
        val enabled = preferences.get(BooleanKey.OApsAIMIwcycle)
        if (!enabled) {
            return WCycleInfo(
                enabled = false,
                dayInCycle = 0,
                phase = CyclePhase.UNKNOWN,
                basalMultiplier = 1.0,
                smbMultiplier = 1.0,
                log = "" // ✅ pas de texte => aucun log
            )
        }

        val startDomPref = preferences.get(DoubleKey.OApsAIMIwcycledateday).toInt()
        if (startDomPref !in 1..31) {
            return WCycleInfo(
                enabled = true,
                dayInCycle = 0,
                phase = CyclePhase.UNKNOWN,
                basalMultiplier = 1.0,
                smbMultiplier = 1.0,
                log = "♀️ WCycle: invalid day"
            )
        }

        val thisMonthStartDom = startDomPref.coerceAtMost(nowDate.lengthOfMonth())
        val candidateThisMonth = nowDate.withDayOfMonth(thisMonthStartDom)
        val cycleStart = if (!candidateThisMonth.isAfter(nowDate)) {
            candidateThisMonth
        } else {
            val prev = nowDate.minusMonths(1)
            prev.withDayOfMonth(startDomPref.coerceAtMost(prev.lengthOfMonth()))
        }

        val days = java.time.temporal.ChronoUnit.DAYS.between(cycleStart, nowDate).toInt()
        val dayInCycle = ((days % 28) + 28) % 28

        val pctMen = preferences.get(DoubleKey.OApsAIMIwcyclemenstruation)    // ex: -5 .. +30
        val pctOvu = preferences.get(DoubleKey.OApsAIMIwcycleovulation)       // ex: -3 .. +30
        val pctLut = preferences.get(DoubleKey.OApsAIMIwcycleluteal)          // ex: +12 .. +30

        val phase = when (dayInCycle) {
            in 0..4   -> CyclePhase.MENSTRUATION
            in 5..12  -> CyclePhase.FOLLICULAR
            in 13..15 -> CyclePhase.OVULATION
            in 16..27 -> CyclePhase.LUTEAL
            else      -> CyclePhase.UNKNOWN
        }

        var basalMul = 1.0
        var smbMul   = 1.0
        val sb = StringBuilder("♀️ Day ${dayInCycle + 1}/28 • ")

        when (phase) {
            CyclePhase.MENSTRUATION -> {
                basalMul *= (1.0 + pctMen / 100.0)
                sb.append("Menstruation: basal ${pctMen}% ")
            }
            CyclePhase.FOLLICULAR -> {
                sb.append("Follicular: neutral ")
            }
            CyclePhase.OVULATION -> {
                smbMul   *= (1.0 + pctOvu / 100.0)
                sb.append("Ovulation: SMB ${pctOvu}% ")
            }
            CyclePhase.LUTEAL -> {
                basalMul *= (1.0 + pctLut / 100.0)
                smbMul   *= 1.05 // conserve la reco +5% (restera borné)
                sb.append("Luteal: basal ${pctLut}%, SMB +5% ")
            }
            CyclePhase.UNKNOWN -> sb.append("Unknown")
        }

        // Bornes ±30%
        basalMul = basalMul.coerceIn(0.7, 1.3)
        smbMul   = smbMul.coerceIn(0.7, 1.3)

        return WCycleInfo(
            enabled = true,
            dayInCycle = dayInCycle,
            phase = phase,
            basalMultiplier = basalMul,
            smbMultiplier = smbMul,
            log = sb.toString()
        )
    }

    /** Applique le multiplicateur basal du cycle et journalise (reason + colon). */
    private fun applyWCycleOnBasal(
        rate: Double,
        bypassSafety: Boolean,
        maxSafe: Double,
        profile: OapsProfileAimi,
        rT: RT
    ): Double {
        val info = computeCurrentWCycleInfo()
        if (!info.enabled) return rate                // ✅ option OFF → silence total
        if (info.basalMultiplier == 1.0) return rate  // neutre → pas de log pour éviter le bruit

        val limit = if (bypassSafety) profile.max_basal else maxSafe
        val adjusted = (rate * info.basalMultiplier).coerceIn(0.0, limit)

        val line = "♀️⚡ ${info.log} Basal×${"%.2f".format(info.basalMultiplier)} → ${"%.2f".format(adjusted)} U/h\n"
        logWCycle(rT.reason, line)
        return adjusted
    }


    /** Applique le multiplicateur SMB du cycle et journalise (reason + colon). */
    private fun applyWCycleOnSmb(smb: Float, reason: StringBuilder?): Float {
        val info = computeCurrentWCycleInfo()
        if (!info.enabled) return smb                // ✅ option OFF → silence total
        if (info.smbMultiplier == 1.0) return smb    // neutre → pas de log

        val out = (smb * info.smbMultiplier.toFloat()).coerceAtLeast(0f)
        val line = "♀️⚡ ${info.log} SMB×${"%.2f".format(info.smbMultiplier)} → ${"%.2f".format(out)} U\n"
        logWCycle(reason, line)
        return out
    }

    private fun calculateDynamicPeakTime(
    currentActivity: Double,
    futureActivity: Double,
    sensorLagActivity: Double,
    historicActivity: Double,
    profile: OapsProfileAimi,
    stepCount: Int? = null, // Nombre de pas
    heartRate: Int? = null, // Rythme cardiaque
    bg: Double,             // Glycémie actuelle
    delta: Double,          // Variation glycémique
    reasonBuilder: StringBuilder // Builder pour accumuler les logs
): Double {
    var dynamicPeakTime = profile.peakTime
    val activityRatio = futureActivity / (currentActivity + 0.0001)

    reasonBuilder.append("🧠 Calcul Dynamic PeakTime\n")
    reasonBuilder.append("  • PeakTime initial: ${profile.peakTime}\n")
    reasonBuilder.append("  • BG: $bg, Delta: ${round(delta, 2)}\n")

    // 1️⃣ Facteur de correction hyperglycémique
    val hyperCorrectionFactor = when {
        bg <= 130 || delta <= 4 -> 1.0
        bg in 130.0..240.0 -> 0.6 - (bg - 130) * (0.6 - 0.3) / (240 - 130)
        else -> 0.3
    }
    dynamicPeakTime *= hyperCorrectionFactor
    reasonBuilder.append("  • Facteur hyperglycémie: $hyperCorrectionFactor\n")

    // 2️⃣ Basé sur currentActivity (IOB)
    if (currentActivity > 0.1) {
        val adjustment = currentActivity * 20 + 5
        dynamicPeakTime += adjustment
        reasonBuilder.append("  • Ajout lié IOB: +$adjustment\n")
    }

    // 3️⃣ Ratio d'activité
    val ratioFactor = when {
        activityRatio > 1.5 -> 0.5 + (activityRatio - 1.5) * 0.05
        activityRatio < 0.5 -> 1.5 + (0.5 - activityRatio) * 0.05
        else -> 1.0
    }
    dynamicPeakTime *= ratioFactor
    reasonBuilder.append("  • Ratio activité: ${round(activityRatio,2)} ➝ facteur $ratioFactor\n")

    // 4️⃣ Nombre de pas
    stepCount?.let {
        when {
            it > 500 -> {
                val stepAdj = it * 0.015
                dynamicPeakTime += stepAdj
                reasonBuilder.append("  • Pas ($it) ➝ +$stepAdj\n")
            }
            it < 100 -> {
                dynamicPeakTime *= 0.9
                reasonBuilder.append("  • Peu de pas ($it) ➝ x0.9\n")
            }
        }
    }

    // 5️⃣ Fréquence cardiaque
    heartRate?.let {
        when {
            it > 110 -> {
                dynamicPeakTime *= 1.15
                reasonBuilder.append("  • FC élevée ($it) ➝ x1.15\n")
            }
            it < 55 -> {
                dynamicPeakTime *= 0.85
                reasonBuilder.append("  • FC basse ($it) ➝ x0.85\n")
            }
        }
    }

    // 6️⃣ Corrélation FC + pas
    if (stepCount != null && heartRate != null) {
        if (stepCount > 1000 && heartRate > 110) {
            dynamicPeakTime *= 1.2
            reasonBuilder.append("  • Activité intense ➝ x1.2\n")
        } else if (stepCount < 200 && heartRate < 50) {
            dynamicPeakTime *= 0.75
            reasonBuilder.append("  • Repos total ➝ x0.75\n")
        }
    }

    this.peakintermediaire = dynamicPeakTime

    // 7️⃣ Sensor lag vs historique
    if (dynamicPeakTime > 40) {
        if (sensorLagActivity > historicActivity) {
            dynamicPeakTime *= 0.85
            reasonBuilder.append("  • SensorLag > Historic ➝ x0.85\n")
        } else if (sensorLagActivity < historicActivity) {
            dynamicPeakTime *= 1.2
            reasonBuilder.append("  • SensorLag < Historic ➝ x1.2\n")
        }
    }

    // 🔚 Clamp entre 35 et 120
    val finalPeak = dynamicPeakTime.coerceIn(35.0, 120.0)
    reasonBuilder.append("  → Résultat PeakTime final : $finalPeak\n")
    return finalPeak
}

    fun detectMealOnset(delta: Float, predictedDelta: Float, acceleration: Float): Boolean {
        val combinedDelta = (delta + predictedDelta) / 2.0f
        return combinedDelta > 3.0f && acceleration > 1.2f
    }

    private fun parseNotes(startMinAgo: Int, endMinAgo: Int): String {
        val olderTimeStamp = now - endMinAgo * 60 * 1000
        val moreRecentTimeStamp = now - startMinAgo * 60 * 1000
        var notes = ""
        val recentNotes2: MutableList<String> = mutableListOf()
        val autoNote = determineNoteBasedOnBg(bg)
        recentNotes2.add(autoNote)
        notes += autoNote  // Ajout de la note auto générée

        recentNotes?.forEach { note ->
            if(note.timestamp > olderTimeStamp && note.timestamp <= moreRecentTimeStamp) {
                val noteText = note.note.lowercase()
                if (noteText.contains("sleep") || noteText.contains("sport") || noteText.contains("snack") || noteText.contains("bfast") || noteText.contains("lunch") || noteText.contains("dinner") ||
                    noteText.contains("lowcarb") || noteText.contains("highcarb") || noteText.contains("meal") || noteText.contains("fasting") ||
                    noteText.contains("low treatment") || noteText.contains("less aggressive") ||
                    noteText.contains("more aggressive") || noteText.contains("too aggressive") ||
                    noteText.contains("normal")) {

                    notes += if (notes.isEmpty()) recentNotes2 else " "
                    notes += note.note
                    recentNotes2.add(note.note)
                }
            }
        }

        notes = processNotesAndCleanUp(notes)
        return notes
    }

    @SuppressLint("NewApi", "DefaultLocale") fun determine_basal(
        glucose_status: GlucoseStatus, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfileAimi, autosens_data: AutosensResult, mealData: MealData,
        microBolusAllowed: Boolean, currentTime: Long, flatBGsDetected: Boolean, dynIsfMode: Boolean
    ): RT {
        consoleError.clear()
        consoleLog.clear()
        var rT = RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            consoleLog = consoleLog,
            consoleError = consoleError
        )
        val reasonAimi = StringBuilder()
        // On définit fromTime pour couvrir une longue période (par exemple, les 7 derniers jours)
        val fromTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
// Récupération des événements de changement de cannule
        val siteChanges = persistenceLayer.getTherapyEventDataFromTime(fromTime, TE.Type.CANNULA_CHANGE, true)

// Calcul de l'âge du site en jours
        val pumpAgeDays: Float = if (siteChanges.isNotEmpty()) {
            // On suppose que la liste est triée par ordre décroissant (le plus récent en premier)
            val latestChangeTimestamp = siteChanges.last().timestamp
            ((System.currentTimeMillis() - latestChangeTimestamp).toFloat() / (1000 * 60 * 60 * 24))
        } else {
            // Si aucun changement n'est enregistré, vous pouvez définir une valeur par défaut
            0f
        }

        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas)
        // Calcul du delta combiné : on combine le delta mesuré et le delta prédit
        val combinedDelta = (delta + predicted) / 2.0f
        val tp = calculateDynamicPeakTime(
            currentActivity = profile.currentActivity,
            futureActivity = profile.futureActivity,
            sensorLagActivity = profile.sensorLagActivity,
            historicActivity = profile.historicActivity,
            profile,
            recentSteps15Minutes,
            averageBeatsPerMinute.toInt(),
            bg,
            combinedDelta,
            reasonAimi
        )
        val autodrive = preferences.get(BooleanKey.OApsAIMIautoDrive)

        val calendarInstance = Calendar.getInstance()
        this.hourOfDay = calendarInstance[Calendar.HOUR_OF_DAY]
        val dayOfWeek = calendarInstance[Calendar.DAY_OF_WEEK]
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        this.bg = glucose_status.glucose
        val getlastBolusSMB = persistenceLayer.getNewestBolusOfType(BS.Type.SMB)
        val lastBolusSMBTime = getlastBolusSMB?.timestamp ?: 0L
        //val lastBolusSMBMinutes = lastBolusSMBTime / 60000
        this.lastBolusSMBUnit = getlastBolusSMB?.amount?.toFloat() ?: 0.0F
        val diff = abs(now - lastBolusSMBTime)
        this.lastsmbtime = (diff / (60 * 1000)).toInt()
        this.maxIob = preferences.get(DoubleKey.ApsSmbMaxIob)
// Tarciso Dynamic Max IOB
        var DinMaxIob = ((bg / 100.0) * (bg / 55.0) + (combinedDelta / 2.0)).toFloat()

// Calcul initial avec un ajustement dynamique basé sur bg et delta
        DinMaxIob = ((bg / 100.0) * (bg / 55.0) + (combinedDelta / 2.0)).toFloat()

// Sécurisation : imposer une borne minimale et une borne maximale
        DinMaxIob = DinMaxIob.coerceAtLeast(1.0f).coerceAtMost(maxIob.toFloat() * 1.3f)

// Réduction de l'augmentation si on est la nuit (0h-6h)
        if (hourOfDay in 0..11 || hourOfDay in 15..19 || hourOfDay >= 22) {
            DinMaxIob = DinMaxIob.coerceAtMost(maxIob.toFloat())
        }

        this.maxIob = if (autodrive) DinMaxIob.toDouble() else maxIob
        rT.reason.append(", MaxIob: $maxIob")
        this.maxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
        this.maxSMBHB = preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)
        // Calcul initial avec ajustement basé sur la glycémie et le delta
        var DynMaxSmb = ((bg / 200) * (bg / 100) + (combinedDelta / 2)).toFloat()

// ⚠ Sécurisation : bornes min/max pour éviter des valeurs extrêmes
        DynMaxSmb = DynMaxSmb.coerceAtLeast(0.1f).coerceAtMost(maxSMBHB.toFloat() * 2.5f)

// ⚠ Ajustement si delta est négatif (la glycémie baisse) pour éviter un SMB trop fort
        if (combinedDelta < 0) {
            DynMaxSmb *= 0.75f // Réduction de 25% si la glycémie baisse
        }

// ⚠ Réduction nocturne pour éviter une surcorrection pendant le sommeil (0h - 6h)
        if (hourOfDay in 0..11 || hourOfDay in 15..19 || hourOfDay >= 22) {
            DynMaxSmb *= 0.6f
        }

// ⚠ Alignement avec `maxSMB` et `profile.peakTime`
        DynMaxSmb = DynMaxSmb.coerceAtMost(maxSMBHB.toFloat() * (tp / 60.0).toFloat())

        //val DynMaxSmb = (bg / 200) * (bg / 100) + (delta / 2)
        val enableUAM = profile.enableUAM

        this.maxSMBHB = if (autodrive && !honeymoon) DynMaxSmb.toDouble() else preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)
        this.maxSMB = if (bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.4 || bg > 180 && honeymoon && mealData.slopeFromMinDeviation >= 1.4) maxSMBHB else maxSMB
        this.tir1DAYabove = tirCalculator.averageTIR(tirCalculator.calculate(1, 65.0, 180.0))?.abovePct()!!
        val tir1DAYIR = tirCalculator.averageTIR(tirCalculator.calculate(1, 65.0, 180.0))?.inRangePct()!!
        this.currentTIRLow = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.belowPct()!!
        this.currentTIRRange = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.inRangePct()!!
        this.currentTIRAbove = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.abovePct()!!
        this.lastHourTIRLow = tirCalculator.averageTIR(tirCalculator.calculateHour(80.0, 140.0))?.belowPct()!!
        val lastHourTIRAbove = tirCalculator.averageTIR(tirCalculator.calculateHour(72.0, 140.0))?.abovePct()
        this.lastHourTIRLow100 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0, 140.0))?.belowPct()!!
        this.lastHourTIRabove170 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0, 170.0))?.abovePct()!!
        this.lastHourTIRabove120 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0, 120.0))?.abovePct()!!
        val tirbasal3IR = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.inRangePct()
        val tirbasal3B = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.belowPct()
        val tirbasal3A = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.abovePct()
        val tirbasalhAP = tirCalculator.averageTIR(tirCalculator.calculateHour(65.0, 100.0))?.abovePct()
        //this.enablebasal = preferences.get(BooleanKey.OApsAIMIEnableBasal)
        this.now = System.currentTimeMillis()
        automateDeletionIfBadDay(tir1DAYIR.toInt())

        this.weekend = if (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY) 1 else 0
        var lastCarbTimestamp = mealData.lastCarbTime
        if (lastCarbTimestamp.toInt() == 0) {
            val oneDayAgoIfNotFound = now - 24 * 60 * 60 * 1000
            lastCarbTimestamp = persistenceLayer.getMostRecentCarbByDate() ?: oneDayAgoIfNotFound
        }
        this.lastCarbAgeMin = ((now - lastCarbTimestamp) / (60 * 1000)).toInt()

        this.futureCarbs = persistenceLayer.getFutureCob().toFloat()
        if (lastCarbAgeMin < 15 && cob == 0.0f) {
            this.cob = persistenceLayer.getMostRecentCarbAmount()?.toFloat() ?: 0.0f
        }

        val fourHoursAgo = now - 4 * 60 * 60 * 1000
        this.recentNotes = persistenceLayer.getUserEntryDataFromTime(fourHoursAgo).blockingGet()

        this.tags0to60minAgo = parseNotes(0, 60)
        this.tags60to120minAgo = parseNotes(60, 120)
        this.tags120to180minAgo = parseNotes(120, 180)
        this.tags180to240minAgo = parseNotes(180, 240)
        this.delta = glucose_status.delta.toFloat()
        this.shortAvgDelta = glucose_status.shortAvgDelta.toFloat()
        this.longAvgDelta = glucose_status.longAvgDelta.toFloat()
        val bgAcceleration = glucose_status.bgAcceleration ?: 0f
        this.bgacc = bgAcceleration.toDouble()
        val therapy = Therapy(persistenceLayer).also {
            it.updateStatesBasedOnTherapyEvents()
        }
        val deleteEventDate = therapy.deleteEventDate
        val deleteTime = therapy.deleteTime
        if (deleteTime) {
            //removeLastNLines(100)
            //createFilteredAndSortedCopy(csvfile,deleteEventDate.toString())
            removeLast200Lines(csvfile)
        }
        this.sleepTime = therapy.sleepTime
        this.snackTime = therapy.snackTime
        this.sportTime = therapy.sportTime
        this.lowCarbTime = therapy.lowCarbTime
        this.highCarbTime = therapy.highCarbTime
        this.mealTime = therapy.mealTime
        this.bfastTime = therapy.bfastTime
        this.lunchTime = therapy.lunchTime
        this.dinnerTime = therapy.dinnerTime
        this.fastingTime = therapy.fastingTime
        this.stopTime = therapy.stopTime
        this.mealruntime = therapy.getTimeElapsedSinceLastEvent("meal")
        this.bfastruntime = therapy.getTimeElapsedSinceLastEvent("bfast")
        this.lunchruntime = therapy.getTimeElapsedSinceLastEvent("lunch")
        this.dinnerruntime = therapy.getTimeElapsedSinceLastEvent("dinner")
        this.highCarbrunTime = therapy.getTimeElapsedSinceLastEvent("highcarb")
        this.snackrunTime = therapy.getTimeElapsedSinceLastEvent("snack")
        this.iscalibration = therapy.calibrationTime
        this.acceleratingUp = if (delta > 2 && delta - longAvgDelta > 2) 1 else 0
        this.decceleratingUp = if (delta > 0 && (delta < shortAvgDelta || delta < longAvgDelta)) 1 else 0
        this.acceleratingDown = if (delta < -2 && delta - longAvgDelta < -2) 1 else 0
        this.decceleratingDown = if (delta < 0 && (delta > shortAvgDelta || delta > longAvgDelta)) 1 else 0
        this.stable = if (delta > -3 && delta < 3 && shortAvgDelta > -3 && shortAvgDelta < 3 && longAvgDelta > -3 && longAvgDelta < 3 && bg < 180) 1 else 0
        //val AutodriveAcceleration = preferences.get(DoubleKey.OApsAIMIAutodriveAcceleration)
        val nightbis = hourOfDay <= 7
        val modesCondition = !mealTime && !lunchTime && !bfastTime && !dinnerTime && !sportTime && !snackTime && !highCarbTime && !sleepTime && !lowCarbTime
        val pbolusAS: Double = preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus)
        val reason = StringBuilder()
        val recentBGs = getRecentBGs()
        val bgTrend = calculateBgTrend(recentBGs, reason)
        val autodriveCondition = adjustAutodriveCondition(bgTrend, predictedBg, combinedDelta.toFloat(),reason)
        if (bg > 100 && predictedBg > 140 && !nightbis && !hasReceivedPbolusMInLastHour(pbolusAS) && autodrive && detectMealOnset(delta, predicted.toFloat(), bgAcceleration.toFloat()) && modesCondition) {
            rT.units = pbolusAS
            rT.reason.append("Autodrive early meal detection/snack: Microbolusing ${pbolusAS}U, CombinedDelta : ${combinedDelta}, Predicted : ${predicted}, Acceleration : ${bgAcceleration}.")
            return rT
        }
        if (isMealModeCondition()) {
            val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
            rT.units = pbolusM
            rT.reason.append("Microbolusing Meal Mode ${pbolusM}U.")
            return rT
        }
        if (!nightbis && isAutodriveModeCondition(delta, autodrive, mealData.slopeFromMinDeviation, bg.toFloat(), predictedBg, reason) && modesCondition) {
            val pbolusA: Double = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
            rT.units = pbolusA
            reason.append("→ Microbolusing Autodrive Mode ${pbolusA}U\n")
            reason.append("  • Target BG: $targetBg\n")
            reason.append("  • Slope from min deviation: ${mealData.slopeFromMinDeviation}\n")
            reason.append("  • BG acceleration: $bgAcceleration\n")

            rT.reason.append(reason.toString()) // une seule fois à la fin
            return rT
            // rT.reason.append("Microbolusing Autodrive Mode ${pbolusA}U. TargetBg : ${targetBg}, CombinedDelta : ${combinedDelta}, Slopemindeviation : ${mealData.slopeFromMinDeviation}, Acceleration : ${bgAcceleration}. ")
            // return rT
        }
        if (isbfastModeCondition()) {
            val pbolusbfast: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
            rT.units = pbolusbfast
            rT.reason.append("Microbolusing 1/2 Breakfast Mode ${pbolusbfast}U.")
            return rT
        }
        if (isbfast2ModeCondition()) {
            val pbolusbfast2: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
            this.maxSMB = pbolusbfast2
            rT.units = pbolusbfast2
            rT.reason.append("Microbolusing 2/2 Breakfast Mode ${pbolusbfast2}U. ")
            return rT
        }
        if (isLunchModeCondition()) {
            val pbolusLunch: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
            rT.units = pbolusLunch
            rT.reason.append("Microbolusing 1/2 Lunch Mode ${pbolusLunch}U.")
            return rT
        }
        if (isLunch2ModeCondition()) {
            val pbolusLunch2: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
            this.maxSMB = pbolusLunch2
            rT.units = pbolusLunch2
            rT.reason.append("Microbolusing 2/2 Lunch Mode ${pbolusLunch2}U.")
            return rT
        }
        if (isDinnerModeCondition()) {
            val pbolusDinner: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
            rT.units = pbolusDinner
            rT.reason.append("Microbolusing 1/2 Dinner Mode ${pbolusDinner}U.")
            return rT
        }
        if (isDinner2ModeCondition()) {
            val pbolusDinner2: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
            this.maxSMB = pbolusDinner2
            rT.units = pbolusDinner2
            rT.reason.append("Microbolusing 2/2 Dinner Mode ${pbolusDinner2}U.")
            return rT
        }
        if (isHighCarbModeCondition()) {
            val pbolusHC: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
            rT.units = pbolusHC
            rT.reason.append("Microbolusing High Carb Mode ${pbolusHC}U.")
            return rT
        }
        if (issnackModeCondition()) {
            val pbolussnack: Double = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
            rT.units = pbolussnack
            rT.reason.append("Microbolusing snack Mode ${pbolussnack}U.")
            return rT
        }
        rT.reason.append(", MaxSMB: $maxSMB")
        var nowMinutes = calendarInstance[Calendar.HOUR_OF_DAY] + calendarInstance[Calendar.MINUTE] / 60.0 + calendarInstance[Calendar.SECOND] / 3600.0
        nowMinutes = (kotlin.math.round(nowMinutes * 100) / 100)  // Arrondi à 2 décimales
        val circadianSensitivity = (0.00000379 * nowMinutes.pow(5)) -
            (0.00016422 * nowMinutes.pow(4)) +
            (0.00128081 * nowMinutes.pow(3)) +
            (0.02533782 * nowMinutes.pow(2)) -
            (0.33275556 * nowMinutes) +
            1.38581503

        val circadianSmb = kotlin.math.round(
            ((0.00000379 * delta * nowMinutes.pow(5)) -
                (0.00016422 * delta * nowMinutes.pow(4)) +
                (0.00128081 * delta * nowMinutes.pow(3)) +
                (0.02533782 * delta * nowMinutes.pow(2)) -
                (0.33275556 * delta * nowMinutes) +
                1.38581503) * 100
        ) / 100  // Arrondi à 2 décimales
        // TODO eliminate
        val deliverAt = currentTime

        // TODO eliminate
        val profile_current_basal = roundBasal(profile.current_basal)
        var basal: Double

        // TODO eliminate
        val systemTime = currentTime

        // TODO eliminate
        val bgTime = glucose_status.date
        val minAgo = round((systemTime - bgTime) / 60.0 / 1000.0, 1)
        // TODO eliminate
        //bg = glucose_status.glucose.toFloat()
        //this.bg = bg.toFloat()
        // TODO eliminate
        val noise = glucose_status.noise
        // 38 is an xDrip error state that usually indicates sensor failure
        // all other BG values between 11 and 37 mg/dL reflect non-error-code BG values, so we should zero temp for those
        if (bg <= 10 || bg == 38.0 || noise >= 3) {  //Dexcom is in ??? mode or calibrating, or xDrip reports high noise
            rT.reason.append("CGM is calibrating, in ??? state, or noise is high")
        }
        if (minAgo > 12 || minAgo < -5) { // Dexcom data is too old, or way in the future
            rT.reason.append("If current system time $systemTime is correct, then BG data is too old. The last BG data was read ${minAgo}m ago at $bgTime")
            // if BG is too old/noisy, or is changing less than 1 mg/dL/5m for 45m, cancel any high temps and shorten any long zero temps
        } else if (bg > 60 && flatBGsDetected) {
            rT.reason.append("Error: CGM data is unchanged for the past ~45m")
        }

        // TODO eliminate
        //val max_iob = profile.max_iob // maximum amount of non-bolus IOB OpenAPS will ever deliver
        val max_iob = maxIob
        //this.maxIob = max_iob
        // if min and max are set, then set target to their average
        var target_bg = (profile.min_bg + profile.max_bg) / 2
        var min_bg = profile.min_bg
        var max_bg = profile.max_bg

        var sensitivityRatio: Double
        val high_temptarget_raises_sensitivity = profile.exercise_mode || profile.high_temptarget_raises_sensitivity
        val normalTarget = if (honeymoon) 130 else 100

        val halfBasalTarget = profile.half_basal_exercise_target

        when {
            !profile.temptargetSet && recentSteps5Minutes >= 0 && (recentSteps30Minutes >= 500 || recentSteps180Minutes > 1500) && recentSteps10Minutes > 0 && predictedBg < 140 -> {
                this.targetBg = 130.0f
            }

            !profile.temptargetSet && predictedBg >= 120 && combinedDelta > 3                                                                                                    -> {
                var baseTarget = if (honeymoon) 110.0 else 70.0
                if (hourOfDay in 0..11 || hourOfDay in 15..19 || hourOfDay >= 22) {
                    baseTarget = if (honeymoon) 110.0 else 90.0
                }
                var hyperTarget = max(baseTarget, profile.target_bg - (bg - profile.target_bg) / 3).toInt()
                hyperTarget = (hyperTarget * min(circadianSensitivity, 1.0)).toInt()
                hyperTarget = max(hyperTarget, baseTarget.toInt())

                this.targetBg = hyperTarget.toFloat()
                target_bg = hyperTarget.toDouble()
                val c = (halfBasalTarget - normalTarget).toDouble()
                sensitivityRatio = c / (c + target_bg - normalTarget)
                // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
                sensitivityRatio = round(sensitivityRatio, 2)
                consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
            }

            !profile.temptargetSet && combinedDelta <= 0 && predictedBg < 120                                                                                                    -> {
                val baseHypoTarget = if (honeymoon) 130.0 else 110.0
                val hypoTarget = baseHypoTarget * max(1.0, circadianSensitivity)
                this.targetBg = min(hypoTarget.toFloat(), 166.0f)
                target_bg = targetBg.toDouble()
                val c = (halfBasalTarget - normalTarget).toDouble()
                sensitivityRatio = c / (c + target_bg - normalTarget)
                // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
                sensitivityRatio = round(sensitivityRatio, 2)
                consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
            }

            else                                                                                                                                                                 -> {
                val defaultTarget = profile.target_bg
                this.targetBg = defaultTarget.toFloat()
                target_bg = targetBg.toDouble()
            }
        }
        if (high_temptarget_raises_sensitivity && profile.temptargetSet && target_bg > normalTarget
            || profile.low_temptarget_lowers_sensitivity && profile.temptargetSet && target_bg < normalTarget
        ) {
            // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
            // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
            //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
            val c = (halfBasalTarget - normalTarget).toDouble()
            sensitivityRatio = c / (c + target_bg - normalTarget)
            // limit sensitivityRatio to profile.autosens_max (1.2x by default)
            sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
            sensitivityRatio = round(sensitivityRatio, 2)
            consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
        } else {
            sensitivityRatio = autosens_data.ratio
            consoleLog.add("Autosens ratio: $sensitivityRatio; ")
        }
        basal = profile.current_basal * sensitivityRatio
        basal = roundBasal(basal)
        if (basal != profile_current_basal)
            consoleLog.add("Adjusting basal from $profile_current_basal to $basal; ")
        else
            consoleLog.add("Basal unchanged: $basal; ")

        // adjust min, max, and target BG for sensitivity, such that 50% increase in ISF raises target from 100 to 120
        if (profile.temptargetSet) {
            consoleLog.add("Temp Target set, not adjusting with autosens")
        } else {
            if (profile.sensitivity_raises_target && autosens_data.ratio < 1 || profile.resistance_lowers_target && autosens_data.ratio > 1) {
                // with a target of 100, default 0.7-1.2 autosens min/max range would allow a 93-117 target range
                min_bg = round((min_bg - 60) / autosens_data.ratio, 0) + 60
                max_bg = round((max_bg - 60) / autosens_data.ratio, 0) + 60
                var new_target_bg = round((target_bg - 60) / autosens_data.ratio, 0) + 60
                // don't allow target_bg below 80
                new_target_bg = max(80.0, new_target_bg)
                if (target_bg == new_target_bg)
                    consoleLog.add("target_bg unchanged: $new_target_bg; ")
                else
                    consoleLog.add("target_bg from $target_bg to $new_target_bg; ")

                target_bg = new_target_bg
            }
        }

        var iob2 = 0.0f
        val iobArray = iob_data_array
        val iob_data = iobArray[0]
        this.iob = iob_data.iob.toFloat()
        if (iob_data.basaliob < 0) {
            iob2 = -iob_data.basaliob.toFloat() + iob
            this.iob = iob2
        }

        val tick: String = if (glucose_status.delta > -0.5) {
            "+" + round(glucose_status.delta)
        } else {
            round(glucose_status.delta).toString()
        }
        val minDelta = min(glucose_status.delta, glucose_status.shortAvgDelta)
        val minAvgDelta = min(glucose_status.shortAvgDelta, glucose_status.longAvgDelta)
        // val maxDelta = max(glucose_status.delta, max(glucose_status.shortAvgDelta, glucose_status.longAvgDelta))
        val tdd7P: Double = preferences.get(DoubleKey.OApsAIMITDD7)
        var tdd7Days = profile.TDD
        if (tdd7Days == 0.0 || tdd7Days < tdd7P) tdd7Days = tdd7P
        this.tdd7DaysPerHour = (tdd7Days / 24).toFloat()

        var tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.data?.totalAmount?.toFloat() ?: 0.0f
        if (tdd2Days == 0.0f || tdd2Days < tdd7P) tdd2Days = tdd7P.toFloat()
        this.tdd2DaysPerHour = tdd2Days / 24

        var tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount?.toFloat() ?: 0.0f
        if (tddDaily == 0.0f || tddDaily < tdd7P / 2) tddDaily = tdd7P.toFloat()
        this.tddPerHour = tddDaily / 24

        var tdd24Hrs = tddCalculator.calculateDaily(-24, 0)?.totalAmount?.toFloat() ?: 0.0f
        if (tdd24Hrs == 0.0f) tdd24Hrs = tdd7P.toFloat()

        this.tdd24HrsPerHour = tdd24Hrs / 24
        var sens = profile.variable_sens
        this.variableSensitivity = sens.toFloat()
        consoleError.add("CR:${profile.carb_ratio}")
        this.predictedBg = predictEventualBG(bg.toFloat(), iob, variableSensitivity, minDelta.toFloat(), shortAvgDelta, longAvgDelta, mealTime, bfastTime, lunchTime, dinnerTime, highCarbTime, snackTime, honeymoon)
        //val insulinEffect = calculateInsulinEffect(bg.toFloat(),iob,variableSensitivity,cob,normalBgThreshold,recentSteps180Minutes,averageBeatsPerMinute.toFloat(),averageBeatsPerMinute10.toFloat(),profile.insulinDivisor.toFloat())

        val now = System.currentTimeMillis()
        val timeMillis5 = now - 5 * 60 * 1000 // 5 minutes en millisecondes
        val timeMillis10 = now - 10 * 60 * 1000 // 10 minutes en millisecondes
        val timeMillis15 = now - 15 * 60 * 1000 // 15 minutes en millisecondes
        val timeMillis30 = now - 30 * 60 * 1000 // 30 minutes en millisecondes
        val timeMillis60 = now - 60 * 60 * 1000 // 60 minutes en millisecondes
        val timeMillis180 = now - 180 * 60 * 1000 // 180 minutes en millisecondes

        val allStepsCounts = persistenceLayer.getStepsCountFromTimeToTime(timeMillis180, now)

        if (preferences.get(BooleanKey.OApsAIMIEnableStepsFromWatch)) {
            allStepsCounts.forEach { stepCount ->
                val timestamp = stepCount.timestamp
                if (timestamp >= timeMillis5) {
                    this.recentSteps5Minutes = stepCount.steps5min
                }
                if (timestamp >= timeMillis10) {
                    this.recentSteps10Minutes = stepCount.steps10min
                }
                if (timestamp >= timeMillis15) {
                    this.recentSteps15Minutes = stepCount.steps15min
                }
                if (timestamp >= timeMillis30) {
                    this.recentSteps30Minutes = stepCount.steps30min
                }
                if (timestamp >= timeMillis60) {
                    this.recentSteps60Minutes = stepCount.steps60min
                }
                if (timestamp >= timeMillis180) {
                    this.recentSteps180Minutes = stepCount.steps180min
                }
            }
        } else {
            this.recentSteps5Minutes = StepService.getRecentStepCount5Min()
            this.recentSteps10Minutes = StepService.getRecentStepCount10Min()
            this.recentSteps15Minutes = StepService.getRecentStepCount15Min()
            this.recentSteps30Minutes = StepService.getRecentStepCount30Min()
            this.recentSteps60Minutes = StepService.getRecentStepCount60Min()
            this.recentSteps180Minutes = StepService.getRecentStepCount180Min()
        }

        try {
            val heartRates5 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis5, now)
            this.averageBeatsPerMinute = heartRates5.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute = 80.0
        }
        try {
            val heartRates10 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis10, now)
            this.averageBeatsPerMinute10 = heartRates10.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute10 = 80.0
        }
        try {
            val heartRates60 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis60, now)
            this.averageBeatsPerMinute60 = heartRates60.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute60 = 80.0
        }
        try {

            val heartRates180 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis180, now)
            this.averageBeatsPerMinute180 = heartRates180.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute180 = 80.0
        }
        if (tdd7Days.toFloat() != 0.0f) {
            basalaimi = (tdd7Days / preferences.get(DoubleKey.OApsAIMIweight)).toFloat()
        }
        this.basalaimi = calculateSmoothBasalRate(tdd7P.toFloat(), tdd7Days.toFloat(), basalaimi)
        if (tdd7Days.toFloat() != 0.0f) {
            this.ci = (450 / tdd7Days).toFloat()
        }

        val choKey: Double = preferences.get(DoubleKey.OApsAIMICHO)
        if (ci != 0.0f && ci != Float.POSITIVE_INFINITY && ci != Float.NEGATIVE_INFINITY) {
            this.aimilimit = (choKey / ci).toFloat()
        } else {
            this.aimilimit = (choKey / profile.carb_ratio).toFloat()
        }
        val timenow = LocalTime.now().hour
        val sixAMHour = LocalTime.of(6, 0).hour

        val pregnancyEnable = preferences.get(BooleanKey.OApsAIMIpregnancy)

        if (tirbasal3B != null && pregnancyEnable && tirbasal3IR != null) {
            this.basalaimi = when {
                tirbasalhAP != null && tirbasalhAP >= 5           -> (basalaimi * 2.0).toFloat()
                lastHourTIRAbove != null && lastHourTIRAbove >= 2 -> (basalaimi * 1.8).toFloat()

                timenow < sixAMHour                               -> {
                    val multiplier = if (honeymoon) 1.2 else 1.4
                    (basalaimi * multiplier).toFloat()
                }

                timenow > sixAMHour                               -> {
                    val multiplier = if (honeymoon) 1.4 else 1.6
                    (basalaimi * multiplier).toFloat()
                }

                tirbasal3B <= 5 && tirbasal3IR in 70.0..80.0      -> (basalaimi * 1.1).toFloat()
                tirbasal3B <= 5 && tirbasal3IR <= 70              -> (basalaimi * 1.3).toFloat()
                tirbasal3B > 5 && tirbasal3A!! < 5                -> (basalaimi * 0.85).toFloat()
                else                                              -> basalaimi
            }
        }

        this.basalaimi = if (honeymoon && basalaimi > profile_current_basal * 2) (profile_current_basal.toFloat() * 2) else basalaimi

        this.basalaimi = if (basalaimi < 0.0f) 0.0f else basalaimi

        this.variableSensitivity = if (honeymoon) {
            if (bg < 150) {
                profile.sens.toFloat() * 1.2f // Légère augmentation pour honeymoon en cas de BG bas
            } else {
                max(
                    profile.sens.toFloat() / 3.0f, // Réduction plus forte en honeymoon
                    sens.toFloat() //* calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat()
                )
            }
        } else {
            if (bg < 100) {
                // 🔹 Correction : Permettre une légère adaptation de l’ISF même en dessous de 100 mg/dL
                profile.sens.toFloat() * 1.1f
            } else if (bg > 120) {
                // 🔹 Si BG > 120, on applique une réduction progressive plus forte
                max(
                    profile.sens.toFloat() / 5.0f,  // 🔥 Réduction plus agressive (divisé par 5)
                    sens.toFloat() //* calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat()
                )
            } else {
                // 🔹 Plage intermédiaire (100-120) avec ajustement plus doux
                sens.toFloat() //* calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat()
            }
        }

// 🔹 Ajustement basé sur l'activité physique : correction plus fine des valeurs
        if (recentSteps5Minutes > 100 && recentSteps10Minutes > 200 && bg < 130 && delta < 10
            || recentSteps180Minutes > 1500 && bg < 130 && delta < 10
        ) {

            this.variableSensitivity *= 1.3f //* calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat() // Réduction du facteur d’augmentation
        }

// 🔹 Réduction du boost si l’activité est modérée pour éviter une ISF excessive
        if (recentSteps30Minutes > 500 && recentSteps5Minutes in 1..99 && bg < 130 && delta < 10) {
            this.variableSensitivity *= 1.2f //* calculateGFactor(delta, lastHourTIRabove120, bg.toFloat()).toFloat()
        }

// 🔹 Sécurisation des bornes minimales et maximales
        this.variableSensitivity = this.variableSensitivity.coerceIn(5.0f, 300.0f)


        sens = variableSensitivity.toDouble()
        //calculate BG impact: the amount BG "should" be rising or falling based on insulin activity alone
        val bgi = round((-iob_data.activity * sens * 5), 2)
        // project deviations for 30 minutes
        var deviation = round(30 / 5 * (minDelta - bgi))
        // don't overreact to a big negative delta: use minAvgDelta if deviation is negative
        if (deviation < 0) {
            deviation = round((30 / 5) * (minAvgDelta - bgi))
            // and if deviation is still negative, use long_avgdelta
            if (deviation < 0) {
                deviation = round((30 / 5) * (glucose_status.longAvgDelta - bgi))
            }
        }
        // calculate the naive (bolus calculator math) eventual BG based on net IOB and sensitivity
        val naive_eventualBG = round(bg - (iob_data.iob * sens), 0)
        // and adjust it for the deviation above
        this.eventualBG = naive_eventualBG + deviation

        // raise target for noisy / raw CGM data
        if (bg > max_bg && profile.adv_target_adjustments && !profile.temptargetSet) {
            // with target=100, as BG rises from 100 to 160, adjustedTarget drops from 100 to 80
            val adjustedMinBG = round(max(80.0, min_bg - (bg - min_bg) / 3.0), 0)
            val adjustedTargetBG = round(max(80.0, target_bg - (bg - target_bg) / 3.0), 0)
            val adjustedMaxBG = round(max(80.0, max_bg - (bg - max_bg) / 3.0), 0)
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedMinBG, don’t use it
            //console.error("naive_eventualBG:",naive_eventualBG+", eventualBG:",eventualBG);
            if (eventualBG > adjustedMinBG && naive_eventualBG > adjustedMinBG && min_bg > adjustedMinBG) {
                consoleLog.add("Adjusting targets for high BG: min_bg from $min_bg to $adjustedMinBG; ")
                min_bg = adjustedMinBG
            } else {
                consoleLog.add("min_bg unchanged: $min_bg; ")
            }
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedTargetBG, don’t use it
            if (eventualBG > adjustedTargetBG && naive_eventualBG > adjustedTargetBG && target_bg > adjustedTargetBG) {
                consoleLog.add("target_bg from $target_bg to $adjustedTargetBG; ")
                target_bg = adjustedTargetBG
            } else {
                consoleLog.add("target_bg unchanged: $target_bg; ")
            }
            // if eventualBG, naive_eventualBG, and max_bg aren't all above adjustedMaxBG, don’t use it
            if (eventualBG > adjustedMaxBG && naive_eventualBG > adjustedMaxBG && max_bg > adjustedMaxBG) {
                consoleError.add("max_bg from $max_bg to $adjustedMaxBG")
                max_bg = adjustedMaxBG
            } else {
                consoleError.add("max_bg unchanged: $max_bg")
            }
        }
        fun safe(v: Double) = if (v.isFinite()) v else Double.POSITIVE_INFINITY
        //val expectedDelta = calculateExpectedDelta(target_bg, eventualBG, bgi)
        val modelcal = calculateSMBFromModel(rT.reason)
        val minBg = minOf(safe(bg), safe(predictedBg.toDouble()), safe(eventualBG))
        val threshold = computeHypoThreshold(minBg, profile.lgsThreshold)

        if (shouldBlockHypoWithHysteresis(
                bg = bg,
                predictedBg = predictedBg.toDouble(),
                eventualBg = eventualBG,
                threshold = threshold,
                deltaMgdlPer5min = delta.toDouble()
            )
        ) {
            rT.reason.appendLine(
                "🛑 Hypo guard+hystérèse: minBG=${convertBG(minBg)} " +
                    "≤ Th=${convertBG(threshold)} (BG=${convertBG(bg)}, pred=${convertBG(predictedBg.toDouble())}, ev=${convertBG(eventualBG)}) → SMB=0"
            )
            this.predictedSMB = 0f
        } else {
            rT.reason.appendLine("💉 SMB (UAM): ${"%.2f".format(modelcal)} U")
            this.predictedSMB = modelcal
        }

        if (preferences.get(BooleanKey.OApsAIMIMLtraining) && csvfile.exists()) {
            val allLines = csvfile.readLines()
            val minutesToConsider = 2500.0
            val linesToConsider = (minutesToConsider / 5).toInt()
            if (allLines.size > linesToConsider) {
                val refinedSMB = neuralnetwork5(combinedDelta.toFloat(), shortAvgDelta, longAvgDelta, predictedSMB, profile)
                rT.reason.appendLine("🧠 NN5 (avant boost): ${"%.2f".format(refinedSMB)} U")
                this.predictedSMB = refinedSMB
                if (bg > 200 && delta > 4 && iob < preferences.get(DoubleKey.ApsSmbMaxIob)) {
                    rT.reason.appendLine("⚡ Boost hyper: x1.7 (BG=${bg.toInt()}, Δ=${"%.1f".format(delta)})")
                    this.predictedSMB *= 1.7f // Augmente de 70% si montée très rapide
                } else if (bg > 180 && delta > 3 && iob < preferences.get(DoubleKey.ApsSmbMaxIob)) {
                    rT.reason.appendLine("⚡ Boost hyper: x1.5 (BG=${bg.toInt()}, Δ=${"%.1f".format(delta)})")
                    this.predictedSMB *= 1.5f // Augmente de 50% si montée modérée
                }

                basal =
                    when {
                        (honeymoon && bg < 170) -> basalaimi * 0.65
                        else                    -> basalaimi.toDouble()
                    }
                basal = roundBasal(basal)
            }
            rT.reason.append("csvfile ${csvfile.exists()}")
        } else {
            rT.reason.appendLine("🗃️ ML training: dataset insuffisant — pas d’affinage")
        }

        var smbToGive = if (bg > 130 && delta > 2 && predictedSMB == 0.0f) modelcal else predictedSMB
        smbToGive = if (honeymoon && bg < 170) smbToGive * 0.8f else smbToGive

        val morningfactor: Double = preferences.get(DoubleKey.OApsAIMIMorningFactor) / 100.0
        val afternoonfactor: Double = preferences.get(DoubleKey.OApsAIMIAfternoonFactor) / 100.0
        val eveningfactor: Double = preferences.get(DoubleKey.OApsAIMIEveningFactor) / 100.0
        val hyperfactor: Double = preferences.get(DoubleKey.OApsAIMIHyperFactor) / 100.0
        val highcarbfactor: Double = preferences.get(DoubleKey.OApsAIMIHCFactor) / 100.0
        val mealfactor: Double = preferences.get(DoubleKey.OApsAIMIMealFactor) / 100.0
        val bfastfactor: Double = preferences.get(DoubleKey.OApsAIMIBFFactor) / 100.0
        val lunchfactor: Double = preferences.get(DoubleKey.OApsAIMILunchFactor) / 100.0
        val dinnerfactor: Double = preferences.get(DoubleKey.OApsAIMIDinnerFactor) / 100.0
        val snackfactor: Double = preferences.get(DoubleKey.OApsAIMISnackFactor) / 100.0
        val sleepfactor: Double = preferences.get(DoubleKey.OApsAIMIsleepFactor) / 100.0

        val adjustedFactors = adjustFactorsBasedOnBgAndHypo(
            morningfactor.toFloat(), afternoonfactor.toFloat(), eveningfactor.toFloat()
        )

        val (adjustedMorningFactor, adjustedAfternoonFactor, adjustedEveningFactor) = adjustedFactors

        // Appliquer les ajustements en fonction de l'heure de la journée
        smbToGive = when {
            bg > 160 && delta > 4 && iob < 0.7 && honeymoon && smbToGive == 0.0f && LocalTime.now().run { (hour in 23..23 || hour in 0..10) } -> 0.15f
            bg > 120 && delta > 8 && iob < 1.0 && !honeymoon && smbToGive < 0.05f                                                             -> profile_current_basal.toFloat()
            highCarbTime                                                                                                                      -> smbToGive * highcarbfactor.toFloat()
            mealTime                                                                                                                          -> smbToGive * mealfactor.toFloat()
            bfastTime                                                                                                                         -> smbToGive * bfastfactor.toFloat()
            lunchTime                                                                                                                         -> smbToGive * lunchfactor.toFloat()
            dinnerTime                                                                                                                        -> smbToGive * dinnerfactor.toFloat()
            snackTime                                                                                                                         -> smbToGive * snackfactor.toFloat()
            sleepTime                                                                                                                         -> smbToGive * sleepfactor.toFloat()
            hourOfDay in 1..11                                                                                                                -> smbToGive * adjustedMorningFactor
            hourOfDay in 12..18                                                                                                               -> smbToGive * adjustedAfternoonFactor
            hourOfDay in 19..23                                                                                                               -> smbToGive * adjustedEveningFactor
            bg > 120 && delta > 7 && !honeymoon                                                                                               -> smbToGive * hyperfactor.toFloat()
            bg > 180 && delta > 5 && iob < 1.2 && honeymoon                                                                                   -> smbToGive * hyperfactor.toFloat()
            else                                                                                                                              -> smbToGive
        }
        val factors = when {
            lunchTime                           -> lunchfactor
            bfastTime                           -> bfastfactor
            dinnerTime                          -> dinnerfactor
            snackTime                           -> snackfactor
            sleepTime                           -> sleepfactor
            hourOfDay in 1..11                  -> adjustedMorningFactor
            hourOfDay in 12..18                 -> adjustedAfternoonFactor
            hourOfDay in 19..23                 -> adjustedEveningFactor
            highCarbTime                        -> highcarbfactor
            mealTime                            -> mealfactor
            bg > 120 && delta > 7 && !honeymoon -> hyperfactor
            else                                -> 1.0
        }
        val currentHour = Calendar.getInstance()[Calendar.HOUR_OF_DAY]
        // Calcul du DIA ajusté en minutes
        val adjustedDIAInMinutes = calculateAdjustedDIA(
            baseDIAHours = profile.dia.toFloat(),
            currentHour = currentHour,
            recentSteps5Minutes = recentSteps5Minutes,
            currentHR = averageBeatsPerMinute.toFloat(),
            averageHR60 = averageBeatsPerMinute60.toFloat(),
            pumpAgeDays = pumpAgeDays
        )
        consoleLog.add("DIA ajusté (en minutes) : $adjustedDIAInMinutes")
//         val actCurr = profile.sensorLagActivity
//         val actFuture = profile.futureActivity
//         val td = adjustedDIAInMinutes
//         val deltaGross = round((glucose_status.delta + actCurr * sens).coerceIn(0.0, 35.0), 1)
//         val actTarget = deltaGross / sens * factors.toFloat()
//         var actMissing = 0.0
//         var deltaScore = 0.5
//
//         if (glucose_status.delta <= 4.0) {
//
//             actMissing = round((actCurr * smbToGive - Math.max(actFuture, 0.0)) / 5, 4)
//             deltaScore = ((bg - target_bg) / 100).coerceIn(0.0, 1.0)
//         } else {
//             actMissing = round((actTarget - Math.max(actFuture, 0.0)) / 5, 4)
//         }
//
//         val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
//         val a = 2 * tau / td
//         val S = 1 / (1 - a + (1 + a) * Math.exp((-td / tau)))
//         var AimiInsReq = actMissing / (S / Math.pow(tau, 2.0) * tp * (1 - tp / td) * Math.exp((-tp / tau)))
//
//         AimiInsReq = if (AimiInsReq < smbToGive) AimiInsReq else smbToGive.toDouble()
//
//         val finalInsulinDose = round(AimiInsReq, 2)
//         // ===== Intégration du module MPC et du correctif PI =====
// // Exemple d’optimisation simple sur la dose basale candidate
//
// // Définition des bornes (par exemple de 0.0 à la basale courante maximale ou une valeur fixée)
//         val doseMin = 0.0
//         val doseMax = maxSMB
// // Paramètres pour le module prédictif
//         val horizon = 30  // horizon en minutes
//         val insulinSensitivity = variableSensitivity.toDouble()  // conversion si nécessaire
//
// // On utilise une recherche itérative simple pour trouver la dose qui minimise le coût
//         var optimalDose = doseMin
//         var bestCost = Double.MAX_VALUE
//         val nSteps = 20  // nombre de pas d’échantillonnage entre doseMin et doseMax
//
//         for (i in 0..nSteps) {
//             val candidate = doseMin + i * (doseMax - doseMin) / nSteps
//             val cost = costFunction(basal, bg.toDouble(), targetBg.toDouble(), horizon, insulinSensitivity, smbToGive.toDouble())
//             if (cost < bestCost) {
//                 bestCost = cost
//                 optimalDose = candidate
//             }
//         }
//
// // Correction en boucle fermée avec un simple contrôleur PI
//         val error = bg.toDouble() - targetBg.toDouble()  // erreur actuelle
//         val Kp = 0.1  // gain proportionnel (à calibrer)
//         val correction = -Kp * error
//
//         val optimalBasalMPC = optimalDose + correction
//
// // On loggue ces valeurs pour debug
//         consoleLog.add("Module MPC: dose candidate = ${optimalDose}, correction = ${correction}, optimalBasalMPC = ${optimalBasalMPC}")
//
// // On peut maintenant utiliser cette dose pour ajuster la décision.
//         smbToGive = optimalBasalMPC.toFloat()
//         rT.reason.appendLine("🎛️ Facteur appliqué → ${"%.2f".format(smbToGive)} U")
// // ===== Fin de l’intégration du module MPC =====
//         smbToGive = applySafetyPrecautions(mealData, finalInsulinDose.toFloat(), rT.reason)
//         rT.reason.appendLine("✅ SMB final: ${"%.2f".format(smbToGive)} U")
//         smbToGive = roundToPoint05(smbToGive)
        val actCurr = profile.sensorLagActivity
        val actFuture = profile.futureActivity
        val td = adjustedDIAInMinutes
        val deltaGross = round((glucose_status.delta + actCurr * sens).coerceIn(0.0, 35.0), 1)
        val actTarget = deltaGross / sens * factors.toFloat()
        var actMissing = 0.0
        var deltaScore = 0.5  // 0..1 : 0 proche/sous target, 1 très au-dessus

        if (glucose_status.delta <= 4.0) {
            actMissing = round((actCurr * smbToGive - max(actFuture, 0.0)) / 5, 4)
            // échelle 0..1 en fonction de l’écart à la cible
            deltaScore = ((bg - target_bg) / 100.0).coerceIn(0.0, 1.0)
        } else {
            actMissing = round((actTarget - max(actFuture, 0.0)) / 5, 4)
        }

// Sécurisation du calcul des constantes (éviter divisions/exp instables)
        val tpD = tp.toDouble()
        val tdD = td.toDouble().coerceAtLeast(tpD * 2.1) // td doit être > 2*tp
        val tau = tpD * (1 - tpD / tdD) / (1 - 2 * tpD / tdD)
        val a = 2 * tau / tdD
        val S = 1 / (1 - a + (1 + a) * Math.exp(-tdD / tau))
        var AimiInsReq = actMissing / (S / (tau * tau) * tpD * (1 - tpD / tdD) * Math.exp(-tpD / tau))

        AimiInsReq = if (AimiInsReq < smbToGive) AimiInsReq else smbToGive.toDouble()
        val finalInsulinDose = round(AimiInsReq, 2)

// ===== Module MPC + correctif PI =====
        val doseMin = 0.0
        val doseMax = maxSMB
        val horizon = 30 // minutes
        val insulinSensitivity = variableSensitivity.toDouble()

        var optimalDose = doseMin
        var bestCost = Double.MAX_VALUE
        val nSteps = 20

        for (i in 0..nSteps) {
            val candidate = doseMin + i * (doseMax - doseMin) / nSteps
            // ⚠️ corriger : évaluer le coût avec "candidate", pas "smbToGive"
            val cost = costFunction(basal, bg.toDouble(), targetBg.toDouble(), horizon, insulinSensitivity, candidate)
            if (cost < bestCost) {
                bestCost = cost
                optimalDose = candidate
            }
        }

// PI : on module Kp par deltaScore (0.5× à 1.5×)
        val baseKp = 0.1
        val Kp = baseKp * (0.5 + deltaScore) // si très haut au-dessus de target ⇒ correction plus énergique
        val error = bg.toDouble() - targetBg.toDouble()
        val correction = -Kp * error

        val optimalBasalMPC = (optimalDose + correction).coerceIn(doseMin, doseMax)

// Log
        consoleLog.add("Module MPC: dose=${"%.2f".format(optimalDose)}, Kp=${"%.3f".format(Kp)}, corr=${"%.2f".format(correction)}, out=${"%.2f".format(optimalBasalMPC)}")

// Mix final entre modèle MPC et estimation "physio" (pondéré par deltaScore)
        val alpha = 0.3 + 0.5 * deltaScore // 0.3..0.8
        var smbDecision = (alpha * optimalBasalMPC + (1 - alpha) * finalInsulinDose).toFloat()

        rT.reason.appendLine("🎛️ MPC/PI → ${"%.2f".format(optimalBasalMPC)} U | physio=${"%.2f".format(finalInsulinDose)} U | α=${"%.2f".format(alpha)}")

// ===== Fin MPC =====

// ⚠️ passer la DECISION courante à la safety (pas finalInsulinDose)
        smbDecision = applySafetyPrecautions(mealData, smbDecision, threshold,rT.reason)
        rT.reason.appendLine("✅ SMB final: ${"%.2f".format(smbDecision)} U")

        smbToGive = roundToPoint05(smbDecision)


        logDataMLToCsv(predictedSMB, smbToGive)
        logDataToCsv(predictedSMB, smbToGive)

        //logDataToCsv(predictedSMB, smbToGive)
        //logDataToCsvHB(predictedSMB, smbToGive)
        val savedReason = rT.reason.toString()
        rT = RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            bg = bg,
            tick = tick,
            eventualBG = eventualBG,
            targetBG = target_bg,
            insulinReq = 0.0,
            deliverAt = deliverAt, // The time at which the microbolus should be delivered
            sensitivityRatio = sensitivityRatio, // autosens ratio (fraction of normal basal)
            consoleLog = consoleLog,
            consoleError = consoleError,
            variable_sens = variableSensitivity.toDouble()
        )
        rT.reason.append(savedReason)
        //rT.reason.append(", DIA ajusté (en minutes) : $adjustedDIAInMinutes, ")
        //rT.reason.append("adjustedMorningFactor ${adjustedMorningFactor}, ")
        //rT.reason.append("adjustedAfternoonFactor ${adjustedAfternoonFactor}, ")
        //rT.reason.append("adjustedEveningFactor ${adjustedEveningFactor}, ")
        //rT.reason.append("Autodrive: $autodrive, autodrivemode : ${isAutodriveModeCondition(delta, autodrive, mealData.slopeFromMinDeviation, bg.toFloat(),predictedBg, reason)}, AutodriveCondition: $autodriveCondition, bgTrend:$bgTrend, Combined Delta: $combinedDelta, PredictedBg: $predictedBg, bgAcceleration: $bgacc, SlopeMinDeviation: ${mealData.slopeFromMinDeviation}")
        //rT.reason.append("TIRBelow: $currentTIRLow, TIRinRange: $currentTIRRange, TIRAbove: $currentTIRAbove")
        //rT.reason.append(reasonAimi.toString())
        rT.reason.appendLine(
    "📈 DIA ajusté: ${"%.1f".format(adjustedDIAInMinutes)} min | " +
    "Morning: ${"%.1f".format(adjustedMorningFactor)}, " +
    "Afternoon: ${"%.1f".format(adjustedAfternoonFactor)}, " +
    "Evening: ${"%.1f".format(adjustedEveningFactor)}"
)

rT.reason.appendLine(
    "🚗 Autodrive: $autodrive | Mode actif: ${isAutodriveModeCondition(delta, autodrive, mealData.slopeFromMinDeviation, bg.toFloat(), predictedBg, reason)} | " +
    "AutodriveCondition: $autodriveCondition"
)

rT.reason.appendLine(
    "🔍 BGTrend: ${"%.2f".format(bgTrend)} | ΔCombiné: ${"%.2f".format(combinedDelta)} | " +
    "Predicted BG: ${"%.0f".format(predictedBg)} | Accélération: ${"%.2f".format(bgacc)} | " +
    "Slope Min Dev.: ${"%.2f".format(mealData.slopeFromMinDeviation)}"
)

rT.reason.appendLine(
    "📊 TIR: <70: ${"%.1f".format(currentTIRLow)}% | 70–180: ${"%.1f".format(currentTIRRange)}% | >180: ${"%.1f".format(currentTIRAbove)}%"
)
        appendCompactLog(reasonAimi, tp, bg, delta, recentSteps5Minutes, averageBeatsPerMinute)
        rT.reason.append(reasonAimi.toString())
        val csf = sens / profile.carb_ratio
        consoleError.add("profile.sens: ${profile.sens}, sens: $sens, CSF: $csf")

        val maxCarbAbsorptionRate = 30 // g/h; maximum rate to assume carbs will absorb if no CI observed
        // limit Carb Impact to maxCarbAbsorptionRate * csf in mg/dL per 5m
        val maxCI = round(maxCarbAbsorptionRate * csf * 5 / 60, 1)
        if (ci > maxCI) {
            consoleError.add("Limiting carb impact from $ci to $maxCI mg/dL/5m ( $maxCarbAbsorptionRate g/h )")
            ci = maxCI.toFloat()
        }
        var remainingCATimeMin = 2.0
        remainingCATimeMin = remainingCATimeMin / sensitivityRatio
        var remainingCATime = remainingCATimeMin
        val totalCI = max(0.0, ci / 5 * 60 * remainingCATime / 2)
        // totalCI (mg/dL) / CSF (mg/dL/g) = total carbs absorbed (g)
        val totalCA = totalCI / csf
        val remainingCarbsCap: Int // default to 90
        remainingCarbsCap = min(90, profile.remainingCarbsCap)
        var remainingCarbs = max(0.0, mealData.mealCOB - totalCA)
        remainingCarbs = min(remainingCarbsCap.toDouble(), remainingCarbs)
        val remainingCIpeak = remainingCarbs * csf * 5 / 60 / (remainingCATime / 2)
        val slopeFromMaxDeviation = mealData.slopeFromMaxDeviation
        val slopeFromMinDeviation = mealData.slopeFromMinDeviation
        val slopeFromDeviations = Math.min(slopeFromMaxDeviation, -slopeFromMinDeviation / 3)
        var IOBpredBGs = mutableListOf<Double>()
        var UAMpredBGs = mutableListOf<Double>()
        var ZTpredBGs = mutableListOf<Double>()

        IOBpredBGs.add(bg)
        ZTpredBGs.add(bg)
        UAMpredBGs.add(bg)
        var ci: Double
        val cid: Double
        // calculate current carb absorption rate, and how long to absorb all carbs
        // CI = current carb impact on BG in mg/dL/5m
        ci = round((minDelta - bgi), 1)
        val uci = round((minDelta - bgi), 1)
        val aci = 8
        if (ci == 0.0) {
            // avoid divide by zero
            cid = 0.0
        } else {
            cid = min(remainingCATime * 60 / 5 / 2, Math.max(0.0, mealData.mealCOB * csf / ci))
        }
        val acid = max(0.0, mealData.mealCOB * csf / aci)
        // duration (hours) = duration (5m) * 5 / 60 * 2 (to account for linear decay)
        consoleError.add("Carb Impact: ${ci} mg/dL per 5m; CI Duration: ${round(cid * 5 / 60 * 2, 1)} hours; remaining CI (~2h peak): ${round(remainingCIpeak, 1)} mg/dL per 5m")
        //console.error("Accel. Carb Impact:",aci,"mg/dL per 5m; ACI Duration:",round(acid*5/60*2,1),"hours");
        var minIOBPredBG = 999.0

        var minUAMPredBG = 999.0
        var minGuardBG: Double

        var minUAMGuardBG = 999.0
        var minIOBGuardBG = 999.0
        var minZTGuardBG = 999.0
        var IOBpredBG: Double = eventualBG
        var maxIOBPredBG = bg

        val lastIOBpredBG: Double

        var lastUAMpredBG: Double? = null
        //var lastZTpredBG: Int
        var UAMduration = 0.0
        var remainingCItotal = 0.0
        val remainingCIs = mutableListOf<Int>()
        val predCIs = mutableListOf<Int>()
        var UAMpredBG: Double? = null


        iobArray.forEach { iobTick ->
            //console.error(iobTick);
            val predBGI: Double = round((-iobTick.activity * sens * 5), 2)
            val IOBpredBGI: Double =
                if (dynIsfMode) round((-iobTick.activity * (1800 / (profile.TDD * (ln((max(IOBpredBGs[IOBpredBGs.size - 1], 39.0) / profile.insulinDivisor) + 1)))) * 5), 2)
                else predBGI
            iobTick.iobWithZeroTemp ?: error("iobTick.iobWithZeroTemp missing")
            // try to find where is crashing https://console.firebase.google.com/u/0/project/androidaps-c34f8/crashlytics/app/android:info.nightscout.androidaps/issues/950cdbaf63d545afe6d680281bb141e5?versions=3.3.0-dev-d%20(1500)&time=last-thirty-days&types=crash&sessionEventKey=673BF7DD032300013D4704707A053273_2017608123846397475
            if (iobTick.iobWithZeroTemp!!.activity.isNaN() || sens.isNaN())
                fabricPrivacy.logCustom("iobTick.iobWithZeroTemp!!.activity=${iobTick.iobWithZeroTemp!!.activity} sens=$sens")
            val predZTBGI =
                if (dynIsfMode) round((-iobTick.iobWithZeroTemp!!.activity * (1800 / (profile.TDD * (ln((max(ZTpredBGs[ZTpredBGs.size - 1], 39.0) / profile.insulinDivisor) + 1)))) * 5), 2)
                else round((-iobTick.iobWithZeroTemp!!.activity * sens * 5), 2)
            val predUAMBGI =
                if (dynIsfMode) round((-iobTick.activity * (1800 / (profile.TDD * (ln((max(UAMpredBGs[UAMpredBGs.size - 1], 39.0) / profile.insulinDivisor) + 1)))) * 5), 2)
                else predBGI
            // for IOBpredBGs, predicted deviation impact drops linearly from current deviation down to zero
            // over 60 minutes (data points every 5m)
            val predDev: Double = ci * (1 - min(1.0, IOBpredBGs.size / (60.0 / 5.0)))
            IOBpredBG = IOBpredBGs[IOBpredBGs.size - 1] + IOBpredBGI + predDev
            // calculate predBGs with long zero temp without deviations
            val ZTpredBG = ZTpredBGs[ZTpredBGs.size - 1] + predZTBGI
            // for UAMpredBGs, predicted carb impact drops at slopeFromDeviations
            // calculate predicted CI from UAM based on slopeFromDeviations
            val predUCIslope = max(0.0, uci + (UAMpredBGs.size * slopeFromDeviations))
            // if slopeFromDeviations is too flat, predicted deviation impact drops linearly from
            // current deviation down to zero over 3h (data points every 5m)
            val predUCImax = max(0.0, uci * (1 - UAMpredBGs.size / max(3.0 * 60 / 5, 1.0)))
            //console.error(predUCIslope, predUCImax);
            // predicted CI from UAM is the lesser of CI based on deviationSlope or DIA
            val predUCI = min(predUCIslope, predUCImax)
            if (predUCI > 0) {
                //console.error(UAMpredBGs.length,slopeFromDeviations, predUCI);
                UAMduration = round((UAMpredBGs.size + 1) * 5 / 60.0, 1)
            }
            UAMpredBG = UAMpredBGs[UAMpredBGs.size - 1] + predUAMBGI + min(0.0, predDev) + predUCI
            //console.error(predBGI, predCI, predUCI);
            // truncate all BG predictions at 4 hours
            if (IOBpredBGs.size < 24) IOBpredBGs.add(IOBpredBG)
            if (UAMpredBGs.size < 24) UAMpredBGs.add(UAMpredBG)
            if (ZTpredBGs.size < 24) ZTpredBGs.add(ZTpredBG)
            // calculate minGuardBGs without a wait from COB, UAM, IOB predBGs
            if (UAMpredBG < minUAMGuardBG) minUAMGuardBG = round(UAMpredBG).toDouble()
            if (IOBpredBG < minIOBGuardBG) minIOBGuardBG = IOBpredBG
            if (ZTpredBG < minZTGuardBG) minZTGuardBG = round(ZTpredBG, 0)

            // set minPredBGs starting when currently-dosed insulin activity will peak
            // look ahead 60m (regardless of insulin type) so as to be less aggressive on slower insulins
            // add 30m to allow for insulin delivery (SMBs or temps)
            this.insulinPeakTime = tp
            val insulinPeak5m = (insulinPeakTime / 60.0) * 12.0
            //console.error(insulinPeakTime, insulinPeak5m, profile.insulinPeakTime, profile.curve);

            // wait 90m before setting minIOBPredBG
            if (IOBpredBGs.size > insulinPeak5m && (IOBpredBG < minIOBPredBG)) minIOBPredBG = round(IOBpredBG, 0)
            if (IOBpredBG > maxIOBPredBG) maxIOBPredBG = IOBpredBG
            if (enableUAM && UAMpredBGs.size > 6 && (UAMpredBG < minUAMPredBG)) minUAMPredBG = round(UAMpredBG, 0)
        }

        rT.predBGs = Predictions()
        IOBpredBGs = IOBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in IOBpredBGs.size - 1 downTo 13) {
            if (IOBpredBGs[i - 1] != IOBpredBGs[i]) break
            else IOBpredBGs.removeAt(IOBpredBGs.lastIndex)
        }
        rT.predBGs?.IOB = IOBpredBGs.map { it.toInt() }
        lastIOBpredBG = round(IOBpredBGs[IOBpredBGs.size - 1]).toDouble()
        ZTpredBGs = ZTpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in ZTpredBGs.size - 1 downTo 7) {
            // stop displaying ZTpredBGs once they're rising and above target
            if (ZTpredBGs[i - 1] >= ZTpredBGs[i] || ZTpredBGs[i] <= target_bg) break
            else ZTpredBGs.removeAt(ZTpredBGs.lastIndex)
        }
        rT.predBGs?.ZT = ZTpredBGs.map { it.toInt() }

        if (ci > 0 || remainingCIpeak > 0) {
            if (enableUAM) {
                UAMpredBGs = UAMpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
                for (i in UAMpredBGs.size - 1 downTo 13) {
                    if (UAMpredBGs[i - 1] != UAMpredBGs[i]) break
                    else UAMpredBGs.removeAt(UAMpredBGs.lastIndex)
                }
                rT.predBGs?.UAM = UAMpredBGs.map { it.toInt() }
                lastUAMpredBG = UAMpredBGs[UAMpredBGs.size - 1]
                eventualBG = max(eventualBG, round(UAMpredBGs[UAMpredBGs.size - 1], 0))
            }

            // set eventualBG based on COB or UAM predBGs
            rT.eventualBG = eventualBG
        }
        //fin predictions
        ////////////////////////////////////////////
        //estimation des glucides nécessaires si risque hypo
        val thresholdBG = 70.0
        val carbsRequired = estimateRequiredCarbs(bg, targetBg.toDouble(), slopeFromDeviations, iob.toDouble(), csf, sens, cob.toDouble())
        val minutesAboveThreshold = calculateMinutesAboveThreshold(bg, slopeFromDeviations, thresholdBG)
        if (carbsRequired >= profile.carbsReqThreshold && minutesAboveThreshold <= 45 && !lunchTime && !dinnerTime && !bfastTime && !highCarbTime && !mealTime) {
            rT.carbsReq = carbsRequired
            rT.carbsReqWithin = minutesAboveThreshold
            rT.reason.append("$carbsRequired add\'l carbs req w/in ${minutesAboveThreshold}m; ")
        }

        val forcedBasalmealmodes = preferences.get(DoubleKey.meal_modes_MaxBasal)
        val forcedBasal = preferences.get(DoubleKey.autodriveMaxBasal)

        val enableSMB = enablesmb(profile, microBolusAllowed, mealData, target_bg)


        rT.COB = mealData.mealCOB
        rT.IOB = iob_data.iob
        rT.reason.append(
            "COB: ${round(mealData.mealCOB, 1).withoutZeros()}, Dev: ${convertBG(deviation.toDouble())}, BGI: ${convertBG(bgi)}, ISF: ${convertBG(sens)}, CR: ${
                round(profile.carb_ratio, 2)
                    .withoutZeros()
            }, Target: ${convertBG(target_bg)}}"
        )
        //val (conditionResult, conditionsTrue) = isCriticalSafetyCondition(mealData, hypoThreshold)
        this.zeroBasalAccumulatedMinutes = getZeroBasalDuration(persistenceLayer, 2)
        // val screenWidth = preferences.get(IntKey.OApsAIMIlogsize)// Largeur d'écran par défaut en caractères si non spécifié
        // val columnWidth = (screenWidth / 2) - 2 // Calcul de la largeur des colonnes en fonction de la largeur de l'écran
        //
        // val aimilog = buildString {
        //     appendLine("╔${"═".repeat(screenWidth)}╗")
        //     appendLine(String.format("║ %-${screenWidth}s ║", "AAPS-MASTER-AIMI"))
        //     appendLine(String.format("║ %-${screenWidth}s ║", "OpenApsAIMI Settings"))
        //     appendLine(String.format("║ %-${screenWidth}s ║", "16 Mai 2025"))
        //     // appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_main_title_1)))
        //     // appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_main_title_2)))
        //     // appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_main_title_3)))
        //     appendLine("╚${"═".repeat(screenWidth)}╝")
        //     appendLine()
        //
        //     appendLine("╔${"═".repeat(screenWidth)}╗")
        //     //appendLine(String.format("║ %-${screenWidth}s ║", "Request"))
        //     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_request_title)))
        //     appendLine("╠${"═".repeat(screenWidth)}╣")
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Reason", "COB: $cob, Dev: $deviation, BGI: $bgi, ISF: $variableSensitivity, CR: $ci, Target: $target_bg"))
        //     appendLine("╚${"═".repeat(screenWidth)}╝")
        //     appendLine()
        //
        //     appendLine("╔${"═".repeat(screenWidth)}╗")
        //     //appendLine(String.format("║ %-${screenWidth}s ║", "SMB Prediction"))
        //     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_smb_prediction_title)))
        //     appendLine("╠${"═".repeat(screenWidth)}╣")
        //     appendLine(String.format("║ %-${columnWidth}s │ %s u", "AI Pred.", String.format("%.2f", predictedSMB)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s u", "Req. SMB", String.format("%.2f", smbToGive)))
        //     appendLine("╚${"═".repeat(screenWidth)}╝")
        //     appendLine()
        //
        //     appendLine("╔${"═".repeat(screenWidth)}╗")
        //     //appendLine(String.format("║ %-${screenWidth}s ║", "Adjusted Factors"))
        //     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_adjusted_factors_title)))
        //     appendLine("╠${"═".repeat(screenWidth)}╣")
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Factors", adjustedFactors))
        //     appendLine("╚${"═".repeat(screenWidth)}╝")
        //     appendLine()
        //
        //     appendLine("╔${"═".repeat(screenWidth)}╗")
        //     //appendLine(String.format("║ %-${screenWidth}s ║", "Limits & Conditions"))
        //     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_limits_title)))
        //     appendLine("╠${"═".repeat(screenWidth)}╣")
        //     appendLine(String.format("║ %-${columnWidth}s │ %s u", "Max IOB", String.format("%.1f", maxIob)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s u", "IOB", String.format("%.1f", iob)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s u", "IOB2", String.format("%.1f", iob2)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s u", "Max SMB", String.format("%.1f", maxSMB)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Safety", conditionResult))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Met", conditionsTrue))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "peakTimeProfile", String.format("%.1f", profile.peakTime)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "currentActivity", String.format("%.1f", profile.currentActivity)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "After IOB Adjustment", String.format("%.1f", peakintermediaire)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Activity Ratio", String.format("%.1f", profile.futureActivity / (profile.currentActivity + 0.0001))))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Final Peak Time after coerceIn", String.format("%.1f", tp)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Adjusted Dia H", String.format("%.1f", adjustedDIAInMinutes/60)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "pumpAgeDays", String.format("%.1f", pumpAgeDays)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "zeroBasalAccumulatedMinutes", String.format("%.1f", zeroBasalAccumulatedMinutes.toDouble())))
        //     appendLine("╚${"═".repeat(screenWidth)}╝")
        //     appendLine()
        //
        //     appendLine("╔${"═".repeat(screenWidth)}╗")
        //     //appendLine(String.format("║ %-${screenWidth}s ║", "Glucose Data"))
        //     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_glucose_title)))
        //     appendLine("╠${"═".repeat(screenWidth)}╣")
        //     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "Current BG", String.format("%.1f", bg)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "predictedBg", String.format("%.1f", predictedBg)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "Target BG", String.format("%.1f", targetBg)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "Prediction", String.format("%.1f", predictedBg)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "Eventual BG", String.format("%.1f", eventualBG)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Delta", String.format("%.1f", delta)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "combinedDelta", String.format("%.1f", combinedDelta)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Short Δ", String.format("%.1f", shortAvgDelta)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Long Δ", String.format("%.1f", longAvgDelta)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "slopeFromMaxDeviation", String.format("%.1f", mealData.slopeFromMaxDeviation)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "slopeFromMinDeviation", String.format("%.1f", mealData.slopeFromMinDeviation)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "bgAcceleration", String.format("%.1f", bgAcceleration)))
        //     appendLine("╚${"═".repeat(screenWidth)}╝")
        //     appendLine()
        //
        //     appendLine("╔${"═".repeat(screenWidth)}╗")
        //     //appendLine(String.format("║ %-${screenWidth}s ║", "TIR Data"))
        //     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_tir_title)))
        //     appendLine("╠${"═".repeat(screenWidth)}╣")
        //     appendLine(String.format("║ %-${columnWidth}s │ %s%%", "TIR Low", String.format("%.1f", currentTIRLow)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s%%", "TIR In Range", String.format("%.1f", currentTIRRange)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s%%", "TIR High", String.format("%.1f", currentTIRAbove)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s%%", "Last Hr TIR Low", String.format("%.1f", lastHourTIRLow)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s%%", "Last Hr TIR >120", String.format("%.1f", lastHourTIRabove120)))
        //     appendLine("╚${"═".repeat(screenWidth)}╝")
        //     appendLine()
        //
        //     appendLine("╔${"═".repeat(screenWidth)}╗")
        //     //appendLine(String.format("║ %-${screenWidth}s ║", "Step Data"))
        //     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_steps_title)))
        //     appendLine("╠${"═".repeat(screenWidth)}╣")
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Steps (5m)", recentSteps5Minutes))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Steps (30m)", recentSteps30Minutes))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Steps (60m)", recentSteps60Minutes))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Steps (180m)", recentSteps180Minutes))
        //     appendLine("╚${"═".repeat(screenWidth)}╝")
        //     appendLine()
        //
        //     appendLine("╔${"═".repeat(screenWidth)}╗")
        //     //appendLine(String.format("║ %-${screenWidth}s ║", "Heart Rate Data"))
        //     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_heart_title)))
        //     appendLine("╠${"═".repeat(screenWidth)}╣")
        //     appendLine(String.format("║ %-${columnWidth}s │ %s bpm", "HR (5m)", String.format("%.1f", averageBeatsPerMinute)))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s bpm", "HR (60m)", String.format("%.1f", averageBeatsPerMinute60)))
        //     appendLine("╚${"═".repeat(screenWidth)}╝")
        //     appendLine()
        //
        //     appendLine("╔${"═".repeat(screenWidth)}╗")
        //     //appendLine(String.format("║ %-${screenWidth}s ║", "Modes"))
        //     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_manual_modes_title)))
        //     appendLine("╠${"═".repeat(screenWidth)}╣")
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Delete Time", if (deleteTime) "Active" else "Inactive"))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Date", deleteEventDate ?: "N/A"))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Sleep", if (sleepTime) "Active" else "Inactive"))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Sport", if (sportTime) "Active" else "Inactive"))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Snack", if (snackTime) "Active" else "Inactive"))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Low Carb", if (lowCarbTime) "Active" else "Inactive"))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "High Carb", if (highCarbTime) "Active" else "Inactive"))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Meal", if (mealTime) "Active" else "Inactive"))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Breakfast", if (bfastTime) "Active" else "Inactive"))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Lunch", if (lunchTime) "Active" else "Inactive"))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Dinner", if (dinnerTime) "Active" else "Inactive"))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Fasting", if (fastingTime) "Active" else "Inactive"))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Calibration", if (iscalibration) "Active" else "Inactive"))
        //     appendLine("╚${"═".repeat(screenWidth)}╝")
        //     appendLine()
        //
        //     appendLine("╔${"═".repeat(screenWidth)}╗")
        //     //appendLine(String.format("║ %-${screenWidth}s ║", "Miscellaneous"))
        //     appendLine(String.format("║ %-${screenWidth}s ║", context.getString(R.string.table_plugin_miscellaneous_title)))
        //     appendLine("╠${"═".repeat(screenWidth)}╣")
        //     appendLine(String.format("║ %-${columnWidth}s │ %s min", "Last SMB", lastsmbtime))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Hour", hourOfDay))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "Weekend", weekend))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "tags0-60m", tags0to60minAgo))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "tags60-120m", tags60to120minAgo))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "tags120-180m", tags120to180minAgo))
        //     appendLine(String.format("║ %-${columnWidth}s │ %s", "tags180-240m", tags180to240minAgo))
        //     appendLine("╚${"═".repeat(screenWidth)}╝")
        //     appendLine()
        //
        //     // Fin de l'assemblage du log
        // }

        // eventual BG is at/above target
        // if iob is over max, just cancel any temps
        if (eventualBG >= max_bg) {
            rT.reason.append("Eventual BG " + convertBG(eventualBG) + " >= " + convertBG(max_bg) + ", ")
        }
        if (iob_data.iob > max_iob) {
            rT.reason.append("IOB ${round(iob_data.iob, 2)} > max_iob $max_iob")
            if (delta < 0) {
                rT.reason.append(", BG is dropping (delta $delta), setting basal to 0. ")
                return setTempBasal(0.0, 30, profile, rT, currenttemp, overrideSafetyLimits = false) // Basal à 0 pendant 30 minutes
            }
            return if (currenttemp.duration > 15 && (roundBasal(basal) == roundBasal(currenttemp.rate))) {
                rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                rT
            } else {
                rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                setTempBasal(basal, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
            }
        } else {
            var insulinReq = smbToGive.toDouble()
            val recentBGValues: List<Float> = getRecentBGs()
            //updateZeroBasalDuration(profile_current_basal)

            val safetyDecision = safetyAdjustment(
                currentBG = bg.toFloat(),
                predictedBG = predictedBg,
                bgHistory = recentBGValues,
                combinedDelta = combinedDelta.toFloat(),
                iob = iob,
                maxIob = maxIob.toFloat(),
                tdd24Hrs = tdd24Hrs,
                tddPerHour = tddPerHour,
                tirInhypo = currentTIRLow.toFloat(),
                targetBG = targetBg,
                zeroBasalDurationMinutes = zeroBasalAccumulatedMinutes
            )

            insulinReq = insulinReq * safetyDecision.bolusFactor
            insulinReq = round(insulinReq, 3)
            rT.insulinReq = insulinReq
            //console.error(iob_data.lastBolusTime);
            // minutes since last bolus
            val lastBolusAge = round((systemTime - iob_data.lastBolusTime) / 60000.0, 1)
            //console.error(lastBolusAge);
            //console.error(profile.temptargetSet, target_bg, rT.COB);
            // only allow microboluses with COB or low temp targets, or within DIA hours of a bolus

            if (microBolusAllowed && enableSMB) {
                val microBolus = insulinReq
                rT.reason.append(" insulinReq $insulinReq")
                if (microBolus >= maxSMB) {
                    rT.reason.append("; maxBolus $maxSMB")
                }
                rT.reason.append(". ")

                // allow SMBIntervals between 1 and 10 minutes
                //val SMBInterval = min(10, max(1, profile.SMBInterval))
                val smbInterval = min(20, max(1, calculateSMBInterval()))
                val nextBolusMins = round(smbInterval - lastBolusAge, 0)
                val nextBolusSeconds = round((smbInterval - lastBolusAge) * 60, 0) % 60
                if (lastBolusAge > smbInterval) {
                    if (microBolus > 0) {
                        rT.units = microBolus
                        rT.reason.append("Microbolusing ${microBolus}U. ")
                    }
                } else {
                    rT.reason.append("Waiting " + nextBolusMins + "m " + nextBolusSeconds + "s to microbolus again. ")
                }

            }

// Calcul du facteur d'ajustement en fonction de la glycémie
// (ici, j'utilise la fonction simplifiée d'interpolation)
            val basalAdjustmentFactor = interpolatebasal(bg)

// Calcul du taux basal final lissé à partir du TDD récent
            val finalBasalRate = computeFinalBasal(bg, tdd7P.toFloat(), tdd7Days.toFloat(), basalaimi)

// Taux basal courant comme valeur de base
            var rate = profile_current_basal
            if (safetyDecision.stopBasal) {
                return setTempBasal(0.0, 30, profile, rT, currenttemp)
            }
            if (safetyDecision.basalLS && combinedDelta in -1.0..3.0 && predictedBg > 130 && iob > 0.1) {
                return setTempBasal(profile_current_basal, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
            }

            // ------------------------------
// 1️⃣ Préparation des variables
            var overrideSafety = false
            var chosenRate: Double? = null

// ------------------------------
// 2️⃣ Early‐meal detection → bypass sécurité, forçage vers `forcedBasal`
            if (detectMealOnset(delta, predicted.toFloat(), bgAcceleration.toFloat())
                && !nightbis && modesCondition && bg > 100 && autodrive
            ) {
                chosenRate = forcedBasal.toDouble()
                overrideSafety = true
                rT.reason.append("Early meal detected → TBR forcée à ${forcedBasal}U/h x30 (override).\n")
            } else {
                // ------------------------------
                // 3️⃣ Cas snack / meal / bfast / lunch / dinner / highCarb / fasting / sport
                chosenRate = when {
                    snackTime && snackrunTime in 0..30 && delta < 10        -> {
                        // pas de bypass pour snack
                        calculateRate(basal, profile_current_basal, 4.0, "SnackTime", currenttemp, rT).toDouble()
                    }

                    mealTime && mealruntime in 0..30 && delta < 10          -> {
                        // meal forcé → bypass
                        overrideSafety = true
                        calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "MealTime", currenttemp, rT).toDouble()
                    }

                    bfastTime && bfastruntime in 0..30 && delta < 10        -> {
                        // breakfast forcé → bypass
                        overrideSafety = true
                        calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "Breakfast", currenttemp, rT).toDouble()
                    }

                    lunchTime && lunchruntime in 0..30 && delta < 10        -> {
                        // lunch forcé → bypass
                        overrideSafety = true
                        calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "Lunch", currenttemp, rT).toDouble()
                    }

                    dinnerTime && dinnerruntime in 0..30 && delta < 10      -> {
                        // dinner forcé → bypass
                        overrideSafety = true
                        calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "Dinner", currenttemp, rT).toDouble()
                    }

                    highCarbTime && highCarbrunTime in 0..30 && delta < 10  -> {
                        // highCarb forcé → bypass
                        overrideSafety = true
                        calculateRate(forcedBasalmealmodes, profile_current_basal, 1.0, "HighCarb", currenttemp, rT).toDouble()
                    }

                    fastingTime                                             ->
                        calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "FastingTime", currenttemp, rT).toDouble()

                    sportTime && bg > 169 && delta > 4                      ->
                        calculateRate(profile_current_basal, profile_current_basal, 1.3, "SportTime", currenttemp, rT).toDouble()

                    honeymoon && delta in 0.0..6.0 && bg in 99.0..141.0     ->
                        calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "Honeymoon", currenttemp, rT).toDouble()

                    bg in 81.0..99.0 && delta in 3.0..7.0 && honeymoon      ->
                        calculateRate(basal, profile_current_basal, 1.0, "Honeymoon small-rise", currenttemp, rT).toDouble()

                    bg > 120 && delta > 0 && smbToGive == 0.0f && honeymoon ->
                        calculateRate(basal, profile_current_basal, 5.0, "Honeymoon corr.", currenttemp, rT).toDouble()

                    else                                                    -> null
                }
            }

// ------------------------------
// 4️⃣ Sécurité immédiate avant hypo : predictedBg<100 & slope négative OU IOB trop haut
            if (chosenRate == null &&
                (predictedBg < 100 && mealData.slopeFromMaxDeviation <= 0 || iob > maxIob)
            ) {
                chosenRate = 0.0
                overrideSafety = false
                rT.reason.append("Safety cut: predictedBg<100 ou IOB>$maxIob → basale à 0.\n")
            }

// ------------------------------
// 5️⃣ Hypoglycémies & basale réduite
            if (chosenRate == null) {
                when {
                    bg < 80.0                                                  -> {
                        chosenRate = 0.0
                        rT.reason.append("BG<80 → basale à 0.\n")
                    }

                    bg in 80.0..90.0 &&
                        slopeFromMaxDeviation <= 0 && iob > 0.1f && !sportTime -> {
                        chosenRate = 0.0
                        rT.reason.append("BG 80-90 & chute → basale à 0.\n")
                    }

                    bg in 80.0..90.0 &&
                        slopeFromMinDeviation >= 0.3 && slopeFromMaxDeviation >= 0 &&
                        combinedDelta in -1.0..2.0 && !sportTime &&
                        bgAcceleration.toFloat() > 0.0f                        -> {
                        chosenRate = profile_current_basal * 0.2
                        rT.reason.append("BG 80-90 stable → basale x0.2.\n")
                    }

                    bg in 90.0..100.0 &&
                        slopeFromMinDeviation <= 0.3 && iob > 0.1f && !sportTime &&
                        bgAcceleration.toFloat() > 0.0f                        -> {
                        chosenRate = 0.0
                        rT.reason.append("BG 90-100 & risque modéré → basale à 0.\n")
                    }

                    bg in 90.0..100.0 &&
                        slopeFromMinDeviation >= 0.3 && combinedDelta in -1.0..2.0 && !sportTime &&
                        bgAcceleration.toFloat() > 0.0f                        -> {
                        chosenRate = profile_current_basal * 0.5
                        rT.reason.append("BG 90-100 gain léger → basale x0.5.\n")
                    }
                }
            }

// ------------------------------
// 6️⃣ Hausses lentes / rapides
            if (chosenRate == null) {
                if (bg > 120 &&
                    slopeFromMinDeviation in 0.4..20.0 &&
                    combinedDelta > 1 && !sportTime &&
                    bgAcceleration.toFloat() > 1.0f
                ) {
                    chosenRate = calculateBasalRate(finalBasalRate, profile_current_basal, combinedDelta.toDouble())
                    rT.reason.append("Montée lente → ajustement proportionnel.\n")
                } else if (eventualBG > 110 && !sportTime && bg > 150 &&
                    combinedDelta in -2.0..15.0 &&
                    bgAcceleration.toFloat() > 0.0f
                ) {
                    chosenRate = calculateBasalRate(finalBasalRate, profile_current_basal, basalAdjustmentFactor)
                    rT.reason.append("EventualBG>110 & hyper → ajustement par facteur.\n")
                }
            }

// ------------------------------
// 7️⃣ Horaires & activité
            if (chosenRate == null) {
                if ((timenow in 11..13 || timenow in 18..21) &&
                    iob < 0.8 && recentSteps5Minutes < 100 &&
                    combinedDelta > -1 && slopeFromMinDeviation > 0.3 &&
                    bgAcceleration.toFloat() > 0.0f
                ) {
                    chosenRate = profile_current_basal * 1.5
                    rT.reason.append("Repas calme & horaire → basale x1.5.\n")
                } else if (timenow > sixAMHour && recentSteps5Minutes > 100) {
                    chosenRate = 0.0
                    rT.reason.append("Activité matinale → basale à 0.\n")
                } else if (timenow <= sixAMHour && delta > 0 && bgAcceleration.toFloat() > 0.0f) {
                    chosenRate = profile_current_basal.toDouble()
                    rT.reason.append("Matinée montante → basale de profil.\n")
                }
            }

// ------------------------------
// 8️⃣ Repas & snacks (boucle)
            if (chosenRate == null) {
                val mealConditions = listOf(
                    snackTime to snackrunTime,
                    mealTime to mealruntime,
                    bfastTime to bfastruntime,
                    lunchTime to lunchruntime,
                    dinnerTime to dinnerruntime,
                    highCarbTime to highCarbrunTime
                )
                for ((meal, runtime) in mealConditions) {
                    if (meal && runtime in 0..30) {
                        chosenRate = calculateBasalRate(finalBasalRate, profile_current_basal, 10.0)
                        rT.reason.append("Repas/snack <30m → basale x10.\n")
                        break
                    } else if (meal && runtime in 30..60 && delta > 0) {
                        chosenRate = calculateBasalRate(finalBasalRate, profile_current_basal, delta.toDouble())
                        rT.reason.append("Repas/snack 30-60m & montée → basale Δ.\n")
                        break
                    }
                }
            }

// ------------------------------
// 9️⃣ Hyperglycémies & corrections
            if (chosenRate == null) {
                when {
                    eventualBG > 180 && delta > 3  ->
                        chosenRate = calculateBasalRate(basalaimi.toDouble(), profile_current_basal, basalAdjustmentFactor).also {
                            rT.reason.append("EventualBG>180 & hyper → ajustement basalaimi.\n")
                        }

                    bg > 180 && delta in -5.0..1.0 ->
                        chosenRate = (profile_current_basal * basalAdjustmentFactor).also {
                            rT.reason.append("BG>180 stable → basale x facteur.\n")
                        }
                }
            }

// ------------------------------
// 🔟 Mode “honeymoon”
            if (chosenRate == null && honeymoon) {
                when {
                    bg in 140.0..169.0 && delta > 0                                                                           ->
                        chosenRate = profile_current_basal.toDouble().also { rT.reason.append("Honeymoon BG 140-169 → profil.\n") }

                    bg > 170 && delta > 0                                                                                     ->
                        chosenRate = calculateBasalRate(finalBasalRate, profile_current_basal, basalAdjustmentFactor).also {
                            rT.reason.append("Honeymoon BG>170 → ajustement.\n")
                        }

                    combinedDelta > 2 && bg in 90.0..119.0                                                                    ->
                        chosenRate = profile_current_basal.toDouble().also { rT.reason.append("Honeymoon Δ>2 & BG 90-119 → profil.\n") }

                    combinedDelta > 0 && bg > 110 && eventualBG > 120 && bg < 160                                             ->
                        chosenRate = profile_current_basal * basalAdjustmentFactor.also { rT.reason.append("Honeymoon corr. mixte.\n") }

                    mealData.slopeFromMaxDeviation > 0 && mealData.slopeFromMinDeviation > 0 && bg > 110 && combinedDelta > 0 ->
                        chosenRate = profile_current_basal * basalAdjustmentFactor.also { rT.reason.append("Honeymoon + repas détection.\n") }

                    mealData.slopeFromMaxDeviation in 0.0..0.2 && mealData.slopeFromMinDeviation in 0.0..0.5 &&
                        bg in 120.0..150.0 && delta > 0                                                                       ->
                        chosenRate = profile_current_basal * basalAdjustmentFactor.also { rT.reason.append("Honeymoon petit slope.\n") }

                    mealData.slopeFromMaxDeviation > 0 && mealData.slopeFromMinDeviation > 0 &&
                        bg in 100.0..120.0 && delta > 0                                                                       ->
                        chosenRate = profile_current_basal * basalAdjustmentFactor.also { rT.reason.append("Honeymoon slope repas.\n") }
                }
            }

// ------------------------------
// 1️⃣1️⃣ Cas grossesse
            if (chosenRate == null && pregnancyEnable && delta > 0 && bg > 110 && !honeymoon) {
                chosenRate = calculateBasalRate(finalBasalRate, profile_current_basal, basalAdjustmentFactor)
                rT.reason.append("Grossesse & Δ>0 → ajustement.\n")
            }

// ------------------------------
// 1️⃣2️⃣ Valeur de repli si aucun cas ne s'est déclenché
            val finalRate = chosenRate ?: profile_current_basal.toDouble()

// ------------------------------
// 1️⃣3️⃣ Appel unique à setTempBasal()
            return setTempBasal(
                _rate = finalRate,
                duration = 30,
                profile = profile,
                rT = rT,
                currenttemp = currenttemp,
                overrideSafetyLimits = overrideSafety
            )

        }
    }
}
