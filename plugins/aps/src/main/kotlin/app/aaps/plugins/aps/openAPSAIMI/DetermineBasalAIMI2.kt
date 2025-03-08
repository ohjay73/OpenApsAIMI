package app.aaps.plugins.aps.openAPSAIMI

import android.annotation.SuppressLint
import android.os.Environment
import app.aaps.core.data.model.BS
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
import org.tensorflow.lite.Interpreter
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

@Singleton
class DetermineBasalaimiSMB2 @Inject constructor(
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy
) {
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var tirCalculator: TirCalculator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    private val consoleError = mutableListOf<String>()
    private val consoleLog = mutableListOf<String>()
    private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    private val modelFile = File(externalDir, "ml/model.tflite")
    private val modelFileUAM = File(externalDir, "ml/modelUAM.tflite")
    private val csvfile = File(externalDir, "oapsaimiML2_records.csv")
    private val tempFile = File(externalDir, "temp.csv")
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
    private var enablebasal: Boolean = false
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

    private fun Double.toFixed2(): String = DecimalFormat("0.00#").format(round(this, 2))

    private fun roundBasal(value: Double): Double = value

    // private fun convertGlucoseToCurrentUnit(value: Double): Double {
    //     return if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
    //         value * MGDL_TO_MMOL
    //     } else {
    //         value
    //     }
    // }
// -- Classe représentant la décision de sécurité --
    data class SafetyDecision(
        val stopBasal: Boolean,      // true => arrête la basale (ou force une basale à 0)
        val bolusFactor: Double,     // Facteur multiplicateur appliqué à la dose SMB (1.0 = dose complète, 0.0 = annulation)
        val reason: String           // Log résumant les critères ayant conduit à la décision
    )

    // -- Calcul de la chute de BG par heure sur une fenêtre donnée (en minutes) --
    fun calculateDropPerHour(bgHistory: List<Float>, windowMinutes: Float): Float {
        if (bgHistory.isEmpty()) return 0f
        val drop = bgHistory.first() - bgHistory.last()  // positif si baisse
        return drop * (60f / windowMinutes)
    }

    // -- Fonction de sécurité qui combine plusieurs indicateurs pour ajuster la dose d'insuline --
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
        targetBG: Float
    ): SafetyDecision {
        val windowMinutes = 30f
        val dropPerHour = calculateDropPerHour(bgHistory, windowMinutes)
        val maxAllowedDropPerHour = 20f  // Ajustez si besoin

        val reasonBuilder = StringBuilder()
        var stopBasal = false
        var bolusFactor = 1.0

        // 1. Contrôle de la chute
        if (dropPerHour >= maxAllowedDropPerHour) {
            // Option A : on arrête complètement la basale
            // stopBasal = true
            // reasonBuilder.append("BG drop élevé: $dropPerHour mg/dL/h; ")

            // Option B : on réduit fortement le bolusFactor sans stopper la basale
            bolusFactor *= 0.3
            reasonBuilder.append("BG drop élevé ($dropPerHour mg/dL/h), forte réduction du bolus; ")
        }

        // 2. Palier sur le combinedDelta
        when {
            combinedDelta < 2f -> {
                bolusFactor *= 0.6
                reasonBuilder.append("combinedDelta très faible ($combinedDelta), réduction x0.6; ")
            }
            combinedDelta < 4f -> {
                bolusFactor *= 0.8
                reasonBuilder.append("combinedDelta modéré ($combinedDelta), réduction x0.8; ")
            }
            else -> {
                reasonBuilder.append("combinedDelta élevé ($combinedDelta), pas de réduction; ")
            }
        }

        // 3. Plateau si BG élevé + combinedDelta très faible
        if (currentBG > 180f && combinedDelta < 2f) {
            bolusFactor *= 0.8
            reasonBuilder.append("Plateau BG>180 & combinedDelta<2 => réduction x0.8; ")
        }

        // 4. Contrôle IOB
        if (iob >= maxIob * 0.85f) {
            bolusFactor *= 0.8
            reasonBuilder.append("IOB élevé ($iob U), réduction x0.8; ")
        }

        // 5. Contrôle du TDD par heure
        val tddThreshold = tdd24Hrs / 24f
        if (tddPerHour > tddThreshold) {
            bolusFactor *= 0.9
            reasonBuilder.append("TDD/h élevé ($tddPerHour U/h), réduction x0.9; ")
        }

        // 6. TIR élevé
        if (tirInhypo >= 10f) {
            bolusFactor *= 0.6
            reasonBuilder.append("TIR élevé ($tirInhypo%), réduction x0.7; ")
        }

        // 7. BG prédit proche de la cible
        if (predictedBG < targetBG + 10) {
            bolusFactor *= 0.5
            reasonBuilder.append("BG prédit ($predictedBG) proche de la cible ($targetBG), réduction x0.5; ")
        }

        return SafetyDecision(
            stopBasal = stopBasal,
            bolusFactor = bolusFactor,
            reason = reasonBuilder.toString()
        )
    }


    // -- Méthode pour obtenir l'historique récent de BG, similaire à getRecentDeltas() --
    private fun getRecentBGs(): List<Float> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        if (data.isEmpty()) return emptyList()

        val nowTimestamp = data.first().timestamp
        val recentBGs = mutableListOf<Float>()

        for (i in 1 until data.size) {
            if (data[i].value > 39 && !data[i].filledGap) {
                val minutesAgo = ((nowTimestamp - data[i].timestamp) / (1000.0 * 60)).toFloat()
                if (minutesAgo in 1.0f..30.0f) {
                    // Utilisation de la valeur recalculée comme BG
                    recentBGs.add(data[i].recalculated.toFloat())
                }
            }
        }
        return recentBGs
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

    private fun enablesmb(profile: OapsProfileAimi, microBolusAllowed: Boolean, mealData: MealData, target_bg: Double): Boolean {
        // disable SMB when a high temptarget is set
        if (!microBolusAllowed) {
            consoleError.add("SMB disabled (!microBolusAllowed)")
            return false
        } else if (!profile.allowSMB_with_high_temptarget && profile.temptargetSet && target_bg > 100) {
            consoleError.add("SMB disabled due to high temptarget of $target_bg")
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
        if (profile.enableSMB_with_temptarget && (profile.temptargetSet && target_bg < 100)) {
            consoleError.add("SMB enabled for temptarget of ${convertBG(target_bg)}")
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

    private fun getMaxSafeBasal(profile: OapsProfileAimi): Double =
        min(profile.max_basal, min(profile.max_daily_safety_multiplier * profile.max_daily_basal, profile.current_basal_safety_multiplier * profile.current_basal))

    fun setTempBasal(_rate: Double, duration: Int, profile: OapsProfileAimi, rT: RT, currenttemp: CurrentTemp): RT {
        val maxSafeBasal = getMaxSafeBasal(profile)
        var rate = _rate

        if (rate < 0) rate = 0.0
        else if (rate > maxSafeBasal) rate = maxSafeBasal

        val suggestedRate = roundBasal(rate)

        if (currenttemp.duration > (duration - 10) && currenttemp.duration <= 120 &&
            suggestedRate <= currenttemp.rate * 1.2 && suggestedRate >= currenttemp.rate * 0.8 &&
            duration > 0) {
            rT.reason.append(" ${currenttemp.duration}m left and ${currenttemp.rate.withoutZeros()} ~ req ${suggestedRate.withoutZeros()}U/hr: no temp required")
        } else if (suggestedRate == profile.current_basal) {
            if (profile.skip_neutral_temps) {
                if (currenttemp.duration > 0) {
                    reason(rT, "Suggested rate is same as profile rate, a temp basal is active, canceling current temp")
                    rT.duration = 0
                    rT.rate = 0.0
                } else {
                    reason(rT, "Suggested rate is same as profile rate, no temp basal is active, doing nothing")
                }
            } else {
                reason(rT, "Setting neutral temp basal of ${profile.current_basal}U/hr")
                rT.duration = duration
                rT.rate = suggestedRate
            }
        } else {
            rT.duration = duration
            rT.rate = suggestedRate
        }
        return rT
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
            csvfile.parentFile?.mkdirs() // Crée le dossier s'il n'existe pas
            csvfile.createNewFile()
            csvfile.appendText(headerRow)
        }
        csvfile.appendText(valuesToRecord + "\n")
    }
    private fun createFilteredAndSortedCopy(dateToRemove: String) {
        if (!csvfile.exists()) {
            println("Le fichier original n'existe pas.")
            return
        }

        try {
            // Lire le fichier original ligne par ligne
            val lines = csvfile.readLines()
            val header = lines.firstOrNull() ?: return
            val dataLines = lines.drop(1)

            // Liste des lignes valides après filtrage
            val validLines = mutableListOf<String>()

            // Filtrer les lignes qui ne correspondent pas à la date à supprimer
            dataLines.forEach { line ->
                val lineParts = line.split(",")
                if (lineParts.isNotEmpty()) {
                    val dateStr = lineParts[0].trim()
                    if (!dateStr.startsWith(dateToRemove)) {
                        validLines.add(line)
                    } else {
                        println("Ligne supprimée : $line")
                    }
                }
            }

            // Trier les lignes par ordre croissant de date (en utilisant les dates en texte)
            validLines.sortBy { it.split(",")[0] }

            if (!tempFile.exists()) {
                tempFile.createNewFile()
            }

            // Écrire les lignes filtrées et triées dans le fichier temporaire
            tempFile.writeText(header + "\n")
            validLines.forEach { line ->
                tempFile.appendText(line + "\n")
            }

            // Obtenir la date et l'heure actuelles pour renommer le fichier original
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
            val currentDateTime = dateFormat.format(Date())
            val backupFileName = "oapsaimiML2_records_$currentDateTime.csv"
            val backupFile = File(externalDir, backupFileName)

            // Renommer le fichier original en fichier de sauvegarde
            if (csvfile.renameTo(backupFile)) {
                // Renommer le fichier temporaire en fichier principal
                if (tempFile.renameTo(csvfile)) {
                    println("Le fichier original a été sauvegardé sous '$backupFileName', et 'temp.csv' a été renommé en 'oapsaimiML2_records.csv'.")
                } else {
                    println("Erreur lors du renommage du fichier temporaire 'temp.csv' en 'oapsaimiML2_records.csv'.")
                }
            } else {
                println("Erreur lors du renommage du fichier original en '$backupFileName'.")
            }

        } catch (e: Exception) {
            println("Erreur lors de la gestion des fichiers : ${e.message}")
        }
    }

    private fun automateDeletionIfBadDay(tir1DAYIR: Int) {
        // Vérifier si le TIR est inférieur à 80
        if (tir1DAYIR < 75) {
            // Vérifier si l'heure actuelle est entre 00:05 et 00:10
            val currentTime = LocalTime.now()
            val start = LocalTime.of(0, 5)
            val end = LocalTime.of(0, 10)

            if (currentTime.isAfter(start) && currentTime.isBefore(end)) {
                // Calculer la date de la veille au format dd/MM/yyyy
                val yesterday = LocalDate.now().minusDays(1)
                val dateToRemove = yesterday.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

                // Appeler la méthode de suppression
                createFilteredAndSortedCopy(dateToRemove)
                println("Les données pour la date $dateToRemove ont été supprimées car TIR1DAIIR est inférieur à 80.")
            } else {
                println("La suppression ne peut être exécutée qu'entre 00:05 et 00:10.")
            }
        } else {
            println("Aucune suppression nécessaire : tir1DAYIR est supérieur ou égal à 85.")
        }
    }

    private fun applySafetyPrecautions(mealData: MealData, smbToGiveParam: Float): Float {
        var smbToGive = smbToGiveParam
        val (conditionResult, _) = isCriticalSafetyCondition(mealData)
        if (conditionResult) return 0.0f
        if (isSportSafetyCondition()) return 0.0f
        // Ajustements basés sur des conditions spécifiques
        smbToGive = applySpecificAdjustments(smbToGive)

        smbToGive = finalizeSmbToGive(smbToGive)
        // Appliquer les limites maximum
        smbToGive = applyMaxLimits(smbToGive)

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
    private fun hasReceivedPbolusMInLastHour(pbolusM: Double): Boolean {
        // Récupère tous les bolus de la dernière heure
        val bolusesLastHour = persistenceLayer
            .getBolusesFromTime(dateUtil.now() - T.hours(1).msecs(), true)
            .blockingGet()
        // Vérifie si un bolus a exactement le montant pbolusM
        return bolusesLastHour.any { it.amount == pbolusM }
    }
    private fun isMealModeCondition2(
        variableSensitivity: Float,
        targetBg: Float,
        delta: Float,
        shortAvgDelta: Float,
        autodrive: Boolean,
        slopeFromMinDeviation: Double,
        bg: Float
    ): Boolean {
        // Récupération de la valeur de pbolusMeal depuis les préférences
        val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
        // Récupération des deltas récents et calcul du delta prédit
        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas)
        // Calcul du delta combiné : combine le delta mesuré et le delta prédit
        val combinedDelta = (delta + predicted) / 2.0f
        // Si un bolus de pbolusM a déjà été administré dans la dernière heure, on ne le ré-administrera pas
        if (hasReceivedPbolusMInLastHour(pbolusM)) {
            return false
        }

        return variableSensitivity in 5.0f..10f &&
            targetBg in 70.0f..85.0f &&
            combinedDelta >= 4 &&
            autodrive &&
            slopeFromMinDeviation >= 1.5 &&
            bg > 120
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
    private fun isCriticalSafetyCondition(mealData: MealData): Pair<Boolean, String> {
        val conditionsTrue = mutableListOf<String>()
        //val slopedeviation = mealData.slopeFromMaxDeviation <= -1.5 && mealData.slopeFromMinDeviation < 0.3
        //if (slopedeviation) conditionsTrue.add("slopedeviation")
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        val nosmbHM = iob > 0.7 && honeymoon && delta <= 10.0 && !mealTime && !bfastTime && !lunchTime && !dinnerTime && eventualBG < 130
        if (nosmbHM) conditionsTrue.add("nosmbHM")
        val honeysmb = honeymoon && delta < 0 && bg < 170
        if (honeysmb) conditionsTrue.add("honeysmb")
        val negdelta = delta <= 0 && !mealTime && !bfastTime && !lunchTime && !dinnerTime && eventualBG < 140
        if (negdelta) conditionsTrue.add("negdelta")
        val nosmb = iob >= 2*maxSMB && bg < 110 && delta < 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (nosmb) conditionsTrue.add("nosmb")
        val fasting = fastingTime
        if (fasting) conditionsTrue.add("fasting")
        val belowMinThreshold = bg < 100 && delta < 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (belowMinThreshold) conditionsTrue.add("belowMinThreshold")
        val isNewCalibration = iscalibration && delta > 8
        if (isNewCalibration) conditionsTrue.add("isNewCalibration")
        val belowTargetAndDropping = bg < targetBg && delta < -2 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (belowTargetAndDropping) conditionsTrue.add("belowTargetAndDropping")
        val belowTargetAndStableButNoCob = bg < targetBg - 15 && shortAvgDelta <= 2 && cob <= 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (belowTargetAndStableButNoCob) conditionsTrue.add("belowTargetAndStableButNoCob")
        val droppingFast = bg < 150 && delta < -2
        if (droppingFast) conditionsTrue.add("droppingFast")
        val droppingFastAtHigh = bg < 220 && delta <= -7
        if (droppingFastAtHigh) conditionsTrue.add("droppingFastAtHigh")
        val droppingVeryFast = delta < -11
        if (droppingVeryFast) conditionsTrue.add("droppingVeryFast")
        val prediction = eventualBG < targetBg && bg < 135 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (prediction) conditionsTrue.add("prediction")
        val interval = eventualBG < targetBg && delta > 10 && iob >= maxSMB/2 && lastsmbtime < 10 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (interval) conditionsTrue.add("interval")
        val targetinterval = targetBg >= 120 && delta > 0 && iob >= maxSMB/2 && lastsmbtime < 12 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        if (targetinterval) conditionsTrue.add("targetinterval")
        //val stablebg = delta>-3 && delta<3 && shortAvgDelta>-3 && shortAvgDelta<3 && longAvgDelta>-3 && longAvgDelta<3 && bg < 120 && !mealTime && !bfastTime && !highCarbTime && !lunchTime && !dinnerTime
        //if (stablebg) conditionsTrue.add("stablebg")
        val acceleratingDown = delta < -2 && delta - longAvgDelta < -2 && lastsmbtime < 15
        if (acceleratingDown) conditionsTrue.add("acceleratingDown")
        val decceleratingdown = delta < 0 && (delta > shortAvgDelta || delta > longAvgDelta) && lastsmbtime < 15
        if (decceleratingdown) conditionsTrue.add("decceleratingdown")
        val nosmbhoneymoon = honeymoon && iob > maxIob / 2 && delta < 0
        if (nosmbhoneymoon) conditionsTrue.add("nosmbhoneymoon")
        val bg90 = bg < 90
        if (bg90) conditionsTrue.add("bg90")
        val result = belowTargetAndDropping || belowTargetAndStableButNoCob || nosmbHM || honeysmb ||
            droppingFast || droppingFastAtHigh || droppingVeryFast || prediction || interval || targetinterval || bg90 || negdelta ||
            fasting || nosmb || isNewCalibration || belowMinThreshold || acceleratingDown || decceleratingdown || nosmbhoneymoon

        val conditionsTrueString = if (conditionsTrue.isNotEmpty()) {
            conditionsTrue.joinToString(", ")
        } else {
            "No conditions met"
        }

        return Pair(result, conditionsTrueString)
    }
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
        // Récupération des intervalles configurés
        val intervalSnack = preferences.get(IntKey.OApsAIMISnackinterval)
        val intervalMeal = preferences.get(IntKey.OApsAIMImealinterval)
        val intervalBF = preferences.get(IntKey.OApsAIMIBFinterval)
        val intervalLunch = preferences.get(IntKey.OApsAIMILunchinterval)
        val intervalDinner = preferences.get(IntKey.OApsAIMIDinnerinterval)
        val intervalSleep = preferences.get(IntKey.OApsAIMISleepinterval)
        val intervalHC = preferences.get(IntKey.OApsAIMIHCinterval)
        val intervalHighBG = preferences.get(IntKey.OApsAIMIHighBGinterval)

        // Par défaut, on part d'un intervalle de base (par exemple 10 minutes)
        var interval = 10

        // Si une des conditions d'intervalle est satisfaite, annuler l'intervalle (0 minute)
        if (shouldApplyIntervalAdjustment(
                intervalSnack, intervalMeal, intervalBF,
                intervalLunch, intervalDinner, intervalSleep,
                intervalHC, intervalHighBG
            )) {
            interval = 0
        }
        // Sinon, si une condition de sécurité s'applique, forcer un intervalle de 10 minutes
        else if (shouldApplySafetyAdjustment()) {
            interval = 10
        }
        // Sinon, si une condition temporelle (ex. heure inappropriée) s'applique, fixer l'intervalle à 10 minutes
        else if (shouldApplyTimeAdjustment()) {
            interval = 10
        }

        // Si une forte activité est détectée via les pas, l'intervalle devient 0 (on annule toute nouvelle administration)
        if (shouldApplyStepAdjustment()) {
            interval = 0
        }

        // Ajustements supplémentaires :
        // Si BG est en dessous de la cible (et donc en chute), augmenter l'intervalle (attendre plus longtemps)
        if (bg < targetBg) {
            interval = (interval * 2).coerceAtMost(20)
        }
        // En mode honeymoon avec BG < 170 et delta faible, attendre plus longtemps
        if (preferences.get(BooleanKey.OApsAIMIhoneymoon) && bg < 170 && delta < 5) {
            interval = (interval * 2).coerceAtMost(20)
        }
        // Si c'est la nuit (par exemple à 23h) et que delta est faible et IOB bas, on réduit légèrement l'intervalle
        val currentHour = LocalTime.now().hour
        if (preferences.get(BooleanKey.OApsAIMInight) && currentHour == 23 && delta < 10 && iob < maxSMB) {
            interval = (interval * 0.8).toInt()
        }

        return interval
    }

    private fun applySpecificAdjustments(smbToGive: Float): Float {
        var result = smbToGive
        val intervalSMBsnack = preferences.get(IntKey.OApsAIMISnackinterval)
        val intervalSMBmeal = preferences.get(IntKey.OApsAIMImealinterval)
        val intervalSMBbfast = preferences.get(IntKey.OApsAIMIBFinterval)
        val intervalSMBlunch = preferences.get(IntKey.OApsAIMILunchinterval)
        val intervalSMBdinner = preferences.get(IntKey.OApsAIMIDinnerinterval)
        val intervalSMBsleep = preferences.get(IntKey.OApsAIMISleepinterval)
        val intervalSMBhc = preferences.get(IntKey.OApsAIMIHCinterval)
        val intervalSMBhighBG = preferences.get(IntKey.OApsAIMIHighBGinterval)
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        val belowTargetAndDropping = bg < targetBg
        val night = preferences.get(BooleanKey.OApsAIMInight)
        val currentHour = LocalTime.now().hour

        when {
            shouldApplyIntervalAdjustment(intervalSMBsnack, intervalSMBmeal, intervalSMBbfast, intervalSMBlunch, intervalSMBdinner, intervalSMBsleep, intervalSMBhc, intervalSMBhighBG) -> {
                result = 0.0f
            }
            shouldApplySafetyAdjustment() -> {
                result *= 0.75f
                this.intervalsmb = 10
            }
            shouldApplyTimeAdjustment() -> {
                result = 0.0f
                this.intervalsmb = 10
            }
        }

        if (shouldApplyStepAdjustment()) result = 0.0f
        if (belowTargetAndDropping) result /= 2
        if (honeymoon && bg < 170 && delta < 5) result /= 2
        if (night && currentHour in 23..23 && delta < 10 && iob < maxSMB) result *= 0.8f
        if (currentHour in 0..7 && delta < 10 && iob < maxSMB) result *= 0.8f // Ajout d'une réduction pendant la période de minuit à 5h du matin

        return result
    }


    private fun shouldApplyIntervalAdjustment(
        intervalSMBsnack: Int, intervalSMBmeal: Int, intervalSMBbfast: Int,
        intervalSMBlunch: Int, intervalSMBdinner: Int, intervalSMBsleep: Int,
        intervalSMBhc: Int, intervalSMBhighBG: Int
    ): Boolean {
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)

        return (lastsmbtime < intervalSMBsnack && snackTime)
            || (lastsmbtime < intervalSMBmeal && mealTime)
            || (lastsmbtime < intervalSMBbfast && bfastTime)
            || (lastsmbtime < intervalSMBlunch && lunchTime)
            || (lastsmbtime < intervalSMBdinner && dinnerTime)
            || (lastsmbtime < intervalSMBsleep && sleepTime)
            || (lastsmbtime < intervalSMBhc && highCarbTime)
            || (!honeymoon && lastsmbtime < intervalSMBhighBG && bg > 120)
            || (honeymoon && lastsmbtime < intervalSMBhighBG && bg > 180)
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
    private fun finalizeSmbToGive(smbToGive: Float): Float {
        var result = smbToGive
        // Assurez-vous que smbToGive n'est pas négatif
        if (result < 0.0f) {
            result = 0.0f
        }
        if (iob < 0 && bg > 100 && delta >= 2 && result == 0.0f) {
            result = 0.1f
        }
        return result
    }
    private fun calculateSMBFromModel(): Float {
        val selectedModelFile: File?
        val modelInputs: FloatArray

        when {
            cob > 0 && lastCarbAgeMin < 240 && modelFile.exists() -> {
                selectedModelFile = modelFile
                modelInputs = floatArrayOf(
                    hourOfDay.toFloat(), weekend.toFloat(),
                    bg.toFloat(), targetBg, iob, cob, lastCarbAgeMin.toFloat(), futureCarbs, delta, shortAvgDelta, longAvgDelta
                )
            }

            modelFileUAM.exists()   -> {
                selectedModelFile = modelFileUAM
                modelInputs = floatArrayOf(
                    hourOfDay.toFloat(), weekend.toFloat(),
                    bg.toFloat(), targetBg, iob, delta, shortAvgDelta, longAvgDelta,
                    tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour,
                    recentSteps5Minutes.toFloat(),recentSteps10Minutes.toFloat(),recentSteps15Minutes.toFloat(),recentSteps30Minutes.toFloat(),recentSteps60Minutes.toFloat(),recentSteps180Minutes.toFloat()
                )
            }

            else                 -> {
                return 0.0F
            }
        }

        val interpreter = Interpreter(selectedModelFile)
        val output = arrayOf(floatArrayOf(0.0F))
        interpreter.run(modelInputs, output)
        interpreter.close()
        var smbToGive = output[0][0].toString().replace(',', '.').toDouble()

        val formatter = DecimalFormat("#.####", DecimalFormatSymbols(Locale.US))
        smbToGive = formatter.format(smbToGive).toDouble()

        return smbToGive.toFloat()
    }

    private fun neuralnetwork5(
    delta: Float,
    shortAvgDelta: Float,
    longAvgDelta: Float,
    predictedSMB: Float,
    profile: OapsProfileAimi
): Float {
    // 1) Configuration générale
    //val minutesToConsider = 5760.0
    //val linesToConsider = (minutesToConsider / 5).toInt()
    val maxIterations = 1000.0
    //val maxGlobalIterations = 5
    //var globalConvergenceReached = false
    //var differenceWithinRange = false

    // Valeur initiale de SMB calculée ailleurs (votre logique existante)
    var finalRefinedSMB: Float = calculateSMBFromModel()

    // 2) Lecture du CSV
    val allLines = csvfile.readLines()
    println("CSV file path: ${csvfile.absolutePath}")

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

    // 3) Préparation des données
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

    // 4) Cross-validation (k-fold)
    val maxK = 10
    val adjustedK = minOf(maxK, inputs.size)
    val foldSize = maxOf(1, inputs.size / adjustedK)

    var bestNetwork: AimiNeuralNetwork? = null
    var bestFoldValLoss = Double.MAX_VALUE

    // 5) Training Config avec learning rate dynamique
    val adjustedLearningRate = if (bestFoldValLoss < 0.01) 0.0005 else 0.001
    val epochs = if (bestFoldValLoss < 0.01) 500 else 1000

    val trainingConfig = TrainingConfig(
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
    )

    // 6) Entraînement & validation
    for (k in 0 until adjustedK) {
        val validationInputs = inputs.subList(k * foldSize, minOf((k + 1) * foldSize, inputs.size))
        val validationTargets = targets.subList(k * foldSize, minOf((k + 1) * foldSize, targets.size))

        val trainingInputs = inputs.minus(validationInputs)
        val trainingTargets = targets.minus(validationTargets)

        if (validationInputs.isEmpty()) continue

        val neuralNetwork = AimiNeuralNetwork(
            inputSize = inputs.first().size,
            hiddenSize = 5,
            outputSize = 1,
            config = trainingConfig,
            regularizationLambda = 0.01
        )

        neuralNetwork.trainWithValidation(trainingInputs, trainingTargets, validationInputs, validationTargets)
        val foldValLoss = neuralNetwork.validate(validationInputs, validationTargets)

        if (foldValLoss < bestFoldValLoss) {
            bestFoldValLoss = foldValLoss
            bestNetwork = neuralNetwork
        }
    }

    // 7) Optimisation finale
    var iterationCount = 0
    do {
        val dynamicThreshold = calculateDynamicThreshold(iterationCount, delta, shortAvgDelta, longAvgDelta)
        val refinedSMB = bestNetwork?.let {
            AimiNeuralNetwork.refineSMB(finalRefinedSMB, it, lastEnhancedInput?.toDoubleArray() ?: DoubleArray(0))
        } ?: finalRefinedSMB

        if (abs(finalRefinedSMB - refinedSMB) <= dynamicThreshold) {
            finalRefinedSMB = max(0.05f, refinedSMB) // Clamp SMB minimum
            break
        }
        iterationCount++
    } while (iterationCount < maxIterations)

    // 8) Condition spéciale sur finalRefinedSMB
    if (finalRefinedSMB > predictedSMB && bg > 150 && delta > 5) {
        println("Modèle prédictif plus élevé, ajustement retenu.")
        return finalRefinedSMB
    }

    // 9) Lissage entre predictedSMB et finalRefinedSMB
    val alpha = 0.7f
    val blendedSMB = alpha * finalRefinedSMB + (1 - alpha) * predictedSMB
    return blendedSMB
}

    private fun calculateDynamicThreshold(
        iterationCount: Int,
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float
    ): Float {
        val baseThreshold = 2.5f

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
    // Méthode pour récupérer les deltas récents (entre 2.5 et 7.5 minutes par exemple)
    private fun getRecentDeltas(): List<Double> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        if (data.isEmpty()) return emptyList()

        val nowTimestamp = data.first().timestamp
        val recentDeltas = mutableListOf<Double>()

        for (i in 1 until data.size) {
            if (data[i].value > 39 && !data[i].filledGap) {
                val minutesAgo = ((nowTimestamp - data[i].timestamp) / (1000.0 * 60)).toFloat()
                if (minutesAgo in 1.0f..15.0f) {
                    val delta = (data.first().recalculated - data[i].recalculated) / minutesAgo * 5f
                    recentDeltas.add(delta)
                }
            }
        }
        return recentDeltas
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
            bg < 120 -> interpolateFactor(bg.toFloat(), 70f, 120f, 0.1f, 0.3f)
            else -> interpolateFactor(bg.toFloat(), 120f, 280f, 0.75f, 2.5f)
        }
        if (honeymoon) factorAdjustment = when {
            bg < 160 -> interpolateFactor(bg.toFloat(), 70f, 160f, 0.2f, 0.4f)
            else -> interpolateFactor(bg.toFloat(), 160f, 250f, 0.4f, 0.65f)
        }
        var bgAdjustment = 1.0f + (deltaAdjustment - 1) * factorAdjustment
        bgAdjustment *= 1.2f

        val dynamicCorrection = when {
            combinedDelta > 11f  -> 2.5f   // Très forte montée, on augmente très agressivement
            combinedDelta > 8f  -> 2.0f   // Montée forte
            combinedDelta > 4f  -> 1.5f   // Montée modérée à forte
            combinedDelta > 2f  -> 0.8f   // Montée légère
            combinedDelta in -2f..2f -> 1.0f  // Stable
            combinedDelta < -2f && combinedDelta >= -4f -> 0.8f  // Baisse légère
            combinedDelta < -4f && combinedDelta >= -6f -> 0.6f  // Baisse modérée
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
            val carbsReq = round(netCarbImpact / csf).toInt()

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
            normalBgThreshold,
            recentSteps180Min,
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


    // private fun predictFutureBg(
    //     bg: Float,
    //     iob: Float,
    //     variableSensitivity: Float,
    //     mealTime: Boolean,
    //     bfastTime: Boolean,
    //     lunchTime: Boolean,
    //     dinnerTime: Boolean,
    //     highcarbTime: Boolean,
    //     snackTime: Boolean
    // ): Float {
    //     val (averageCarbAbsorptionTime, carbTypeFactor, estimatedCob) = when {
    //         highcarbTime -> Triple(3.5f, 0.75f, 100f) // Repas riche en glucides
    //         snackTime -> Triple(1.5f, 1.25f, 15f) // Snack
    //         mealTime -> Triple(2.5f, 1.0f, 55f) // Repas normal
    //         bfastTime -> Triple(3.5f, 1.0f, 55f) // Petit-déjeuner
    //         lunchTime -> Triple(2.5f, 1.0f, 70f) // Déjeuner
    //         dinnerTime -> Triple(2.5f, 1.0f, 70f) // Dîner
    //         else -> Triple(2.5f, 1.0f, 70f) // Valeur par défaut si aucun type de repas spécifié
    //     }
    //     val insulinEffect = calculateInsulinEffect(
    //         bg, iob, variableSensitivity, cob, normalBgThreshold, recentSteps180Minutes,
    //         averageBeatsPerMinute.toFloat(), averageBeatsPerMinute10.toFloat(), insulinPeakTime.toFloat()
    //     )
    //
    //
    //     val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
    //     var futureBg = bg - insulinEffect
    //     if (!honeymoon && futureBg < 39f) {
    //         futureBg = 39f
    //     } else if (honeymoon && futureBg < 50f) {
    //         futureBg = 50f
    //     }
    //
    //     return futureBg
    // }
    // private fun predictEventualBG(
    //     bg: Float,                     // Glycémie actuelle
    //     iob: Float,                    // Insuline active (IOB)
    //     variableSensitivity: Float,    // Sensibilité insulinique
    //     minDelta: Float,               // Delta minimal instantané (ex. dernière variation)
    //     minAvgDelta: Float,            // Moyenne instantanée (court terme) des deltas
    //     longAvgDelta: Float,           // Moyenne à plus long terme des deltas
    //     mealTime: Boolean,
    //     bfastTime: Boolean,
    //     lunchTime: Boolean,
    //     dinnerTime: Boolean,
    //     highCarbTime: Boolean,
    //     snackTime: Boolean,
    //     honeymoon: Boolean
    // ): Float {
    //     // 1. Détermination des paramètres glucidiques en fonction du contexte (type de repas)
    //     val (averageCarbAbsorptionTime, carbTypeFactor, estimatedCob) = when {
    //         highCarbTime -> Triple(3.5f, 0.75f, 100f) // Repas riche en glucides
    //         snackTime    -> Triple(1.5f, 1.25f, 15f)   // Snack
    //         mealTime     -> Triple(2.5f, 1.0f, 55f)     // Repas standard
    //         bfastTime    -> Triple(3.5f, 1.0f, 55f)     // Petit-déjeuner
    //         lunchTime    -> Triple(2.5f, 1.0f, 70f)     // Déjeuner
    //         dinnerTime   -> Triple(2.5f, 1.0f, 70f)     // Dîner
    //         else         -> Triple(2.5f, 1.0f, 70f)     // Valeur par défaut
    //     }
    //
    //     // 2. Calcul du temps d'absorption (en minutes) en fonction de l'heure actuelle
    //     val currentHour = LocalTime.now().hour
    //
    //     // 3. Calcul de l'effet insuline (modèle simplifié)
    //     val insulinEffect = iob * variableSensitivity
    //
    //
    //     // 5. Calcul de la déviation basée sur la tendance
    //     //    On utilise minDelta et, si nécessaire, on remplace par minAvgDelta ou longAvgDelta pour limiter l'effet négatif excessif.
    //     var deviation = (30f / 5f) * (minDelta - bg)
    //     if (deviation < 0) {
    //         deviation = (30f / 5f) * (minAvgDelta - bg)
    //         if (deviation < 0) {
    //             deviation = (30f / 5f) * (longAvgDelta - bg)
    //         }
    //     }
    //
    //     // 6. Calcul de la prédiction naïve basée sur l'effet insuline
    //     val naiveEventualBG = round((bg - insulinEffect).toDouble(), 0)
    //     val predictedfuturBG = predictFutureBg(bg,iob,variableSensitivity,mealTime,bfastTime,lunchTime,dinnerTime,highCarbTime,snackTime)
    //     val maxpred = max(naiveEventualBG.toFloat(),predictedfuturBG)
    //     // 7. Combinaison des effets pour obtenir la glycémie prédite :
    //     //    glycémie actuelle - effet insuline + effet glucidique + déviation (tendance)
    //     val predictedBG = maxpred + deviation
    //
    //     // 8. Application d'un seuil minimal de sécurité :
    //     //    - En mode non-honeymoon, ne pas prédire en dessous de 39 mg/dL
    //     //    - En mode honeymoon, ne pas prédire en dessous de 50 mg/dL
    //     val finalPredictedBG = when {
    //         !honeymoon && predictedBG < 39f -> 39f
    //         honeymoon && predictedBG < 50f   -> 50f
    //         else                              -> predictedBG
    //     }
    //
    //     return finalPredictedBG as Float
    // }
    private fun predictFutureBg(
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        mealTime: Boolean,
        bfastTime: Boolean,
        lunchTime: Boolean,
        dinnerTime: Boolean,
        highcarbTime: Boolean,
        snackTime: Boolean
    ): Float {
        // Les paramètres glucidiques sont définis selon le contexte, même s'ils ne sont pas utilisés ici.
        val (averageCarbAbsorptionTime, carbTypeFactor, estimatedCob) = when {
            highcarbTime -> Triple(3.5f, 0.75f, 100f) // Repas riche en glucides
            snackTime    -> Triple(1.5f, 1.25f, 15f)   // Snack
            mealTime     -> Triple(2.5f, 1.0f, 55f)     // Repas normal
            bfastTime    -> Triple(3.5f, 1.0f, 55f)     // Petit-déjeuner
            lunchTime    -> Triple(2.5f, 1.0f, 70f)     // Déjeuner
            dinnerTime   -> Triple(2.5f, 1.0f, 70f)     // Dîner
            else         -> Triple(2.5f, 1.0f, 70f)     // Valeur par défaut
        }

        // Ici, on suppose que calculateInsulinEffect() est déjà calibré (même s'il utilise encore 'cob', etc.)
        val insulinEffect = calculateInsulinEffect(
            bg, iob, variableSensitivity, cob, normalBgThreshold, recentSteps180Minutes,
            averageBeatsPerMinute.toFloat(), averageBeatsPerMinute10.toFloat(), insulinPeakTime.toFloat()
        )

        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        var futureBg = bg - insulinEffect

        // Sécurité : on ne prédit pas en dessous d'un certain seuil
        if (!honeymoon && futureBg < 39f) {
            futureBg = 39f
        } else if (honeymoon && futureBg < 50f) {
            futureBg = 50f
        }

        return futureBg
    }

    private fun predictEventualBG(
        bg: Float,                     // Glycémie actuelle
        iob: Float,                    // Insuline active (IOB)
        variableSensitivity: Float,    // Sensibilité insulinique
        minDelta: Float,               // Delta instantané (mg/dL par intervalle)
        minAvgDelta: Float,            // Delta moyen court terme
        longAvgDelta: Float,           // Delta moyen long terme
        mealTime: Boolean,
        bfastTime: Boolean,
        lunchTime: Boolean,
        dinnerTime: Boolean,
        highCarbTime: Boolean,
        snackTime: Boolean,
        honeymoon: Boolean
    ): Float {
        // 1. Détermination des paramètres glucidiques en fonction du contexte (pour cohérence)
        val (averageCarbAbsorptionTime, carbTypeFactor, estimatedCob) = when {
            highCarbTime -> Triple(3.5f, 0.75f, 100f)
            snackTime    -> Triple(1.5f, 1.25f, 15f)
            mealTime     -> Triple(2.5f, 1.0f, 55f)
            bfastTime    -> Triple(3.5f, 1.0f, 55f)
            lunchTime    -> Triple(2.5f, 1.0f, 70f)
            dinnerTime   -> Triple(2.5f, 1.0f, 70f)
            else         -> Triple(2.5f, 1.0f, 70f)
        }

        // 2. (Optionnel) On pourrait calculer ici un temps d'absorption en fonction de l'heure, mais il n'est pas utilisé
        val currentHour = LocalTime.now().hour

        // 3. Calcul de l'effet insuline (modèle simplifié)
        val insulinEffect = iob * variableSensitivity

        // 4. Calcul de la déviation basée sur la tendance
        // L'idée est de prévoir l'effet sur 30 minutes (soit 6 intervalles de 5 minutes)
        // On multiplie directement le delta par 6, sans soustraire bg qui était trop pénalisant.
        var deviation = (30f / 5f) * minDelta  // 6 * minDelta
        if (deviation < 0) {
            deviation = (30f / 5f) * minAvgDelta
            if (deviation < 0) {
                deviation = (30f / 5f) * longAvgDelta
            }
        }

        // 5. Calcul de la prédiction naïve basée sur l'effet insuline
        val naiveEventualBG = round((bg - insulinEffect).toDouble(), 0).toFloat()
        // 6. Appel de predictFutureBg qui applique son propre modèle
        val predictedFutureBG = predictFutureBg(bg, iob, variableSensitivity, mealTime, bfastTime, lunchTime, dinnerTime, highCarbTime, snackTime)
        // On prend la valeur maximale entre les deux prédictions pour éviter d'être trop pessimiste.
        val maxPred = max(naiveEventualBG, predictedFutureBG)

        // 7. Combinaison : on ajoute le décalage (déviation) à la meilleure estimation
        val predictedBG = maxPred + deviation

        // 8. Application d'un seuil minimal de sécurité
        val finalPredictedBG = when {
            !honeymoon && predictedBG < 39f -> 39f
            honeymoon && predictedBG < 50f   -> 50f
            else                              -> predictedBG
        }

        return finalPredictedBG
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
        val higherBasalRangeWeight: Double = 1.5 // Facteur pour les glycémies supérieures à 100 mg/dL
        val lowerBasalRangeWeight: Double = 0.8 // Facteur pour les glycémies inférieures à 100 mg/dL mais supérieures ou égales à 80

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
   private fun calculateDynamicPeakTime(
    currentActivity: Double,
    futureActivity: Double,
    sensorLagActivity: Double,
    historicActivity: Double,
    profile: OapsProfileAimi,
    stepCount: Int? = null, // Nombre de pas
    heartRate: Int? = null, // Rythme cardiaque
    bg: Double,             // Glycémie actuelle
    delta: Double           // Variation glycémique
): Double {
    var dynamicPeakTime = profile.peakTime
    val activityRatio = futureActivity / (currentActivity + 0.0001)

       // Calcul d'un facteur de correction hyperglycémique de façon continue
       val hyperCorrectionFactor = when {
           bg <= 130 || delta <= 4 -> 1.0
           bg in 130.0..240.0 -> {
               // Le multiplicateur passe de 0.6 à 0.3 quand bg évolue de 130 à 240
               0.6 - (bg - 130) * (0.6 - 0.3) / (240 - 130)
           }
           else -> 0.3
       }
       dynamicPeakTime *= hyperCorrectionFactor

    // 2️⃣ **Ajustement basé sur l'IOB (currentActivity)**
    if (currentActivity > 0.1) {
        dynamicPeakTime += currentActivity * 20 + 5 // Ajuster proportionnellement à l'activité
    }

    // 3️⃣ **Ajustement basé sur le ratio d'activité**
    dynamicPeakTime *= when {
        activityRatio > 1.5 -> 0.5 + (activityRatio - 1.5) * 0.05
        activityRatio < 0.5 -> 1.5 + (0.5 - activityRatio) * 0.05
        else -> 1.0
    }

    // 4️⃣ **Ajustement basé sur le nombre de pas**
    stepCount?.let {
        if (it > 500) {
            dynamicPeakTime += it * 0.015 // Ajustement proportionnel plus agressif
        } else if (it < 100) {
            dynamicPeakTime *= 0.9 // Réduction du peakTime si peu de mouvement
        }
    }

    // 5️⃣ **Ajustement basé sur le rythme cardiaque**
    heartRate?.let {
        if (it > 110) {
            dynamicPeakTime *= 1.15 // Augmenter le peakTime de 15% si FC élevée
        } else if (it < 55) {
            dynamicPeakTime *= 0.85 // Réduire le peakTime de 15% si FC basse
        }
    }

    // 6️⃣ **Corrélation entre pas et rythme cardiaque**
    if (stepCount != null && heartRate != null) {
        if (stepCount > 1000 && heartRate > 110) {
            dynamicPeakTime *= 1.2 // Augmenter peakTime si activité intense
        } else if (stepCount < 200 && heartRate < 50) {
            dynamicPeakTime *= 0.75 // Réduction plus forte si repos total
        }
    }

    this.peakintermediaire = dynamicPeakTime

    // 7️⃣ **Ajustement basé sur le retard capteur (sensor lag) et historique**
    if (dynamicPeakTime > 40) {
        if (sensorLagActivity > historicActivity) {
            dynamicPeakTime *= 0.85
        } else if (sensorLagActivity < historicActivity) {
            dynamicPeakTime *= 1.2
        }
    }

    // 🔥 **Limiter le peakTime à des valeurs réalistes (35-120 min)**
    return dynamicPeakTime.coerceIn(35.0, 120.0)
}

    fun detectMealOnset(delta: Float, predictedDelta: Float, acceleration: Float): Boolean {
        val combinedDelta = (delta + predictedDelta) / 2.0f
        return combinedDelta > 4.0f && acceleration > 1.0f
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

    @SuppressLint("NewApi") fun determine_basal(
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
            combinedDelta.toDouble()
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
        DinMaxIob = DinMaxIob.coerceAtLeast(1.0f).coerceAtMost(maxIob.toFloat() * 1.5f)

// Réduction de l'augmentation si on est la nuit (0h-6h)
        if (hourOfDay in 0..8) {
            DinMaxIob = DinMaxIob.coerceAtMost(maxIob.toFloat())
        }

        this.maxIob = if (autodrive) DinMaxIob.toDouble() else maxIob
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
        if (hourOfDay in 0..8) {
            DynMaxSmb *= 0.6f
        }

// ⚠ Alignement avec `maxSMB` et `profile.peakTime`
        DynMaxSmb = DynMaxSmb.coerceAtMost(maxSMBHB.toFloat() * (tp / 60.0).toFloat())


        //val DynMaxSmb = (bg / 200) * (bg / 100) + (delta / 2)
        val enableUAM = profile.enableUAM

        this.maxSMBHB = if (autodrive) DynMaxSmb.toDouble() else preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)
        this.maxSMB = if (bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >=1.4 || bg > 180 && honeymoon && mealData.slopeFromMinDeviation >= 1.4) maxSMBHB else maxSMB
        this.tir1DAYabove = tirCalculator.averageTIR(tirCalculator.calculate(1, 65.0, 180.0))?.abovePct()!!
        val tir1DAYIR = tirCalculator.averageTIR(tirCalculator.calculate(1, 65.0, 180.0))?.inRangePct()!!
        this.currentTIRLow = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.belowPct()!!
        this.currentTIRRange = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.inRangePct()!!
        this.currentTIRAbove = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.abovePct()!!
        this.lastHourTIRLow = tirCalculator.averageTIR(tirCalculator.calculateHour(80.0,140.0))?.belowPct()!!
        val lastHourTIRAbove = tirCalculator.averageTIR(tirCalculator.calculateHour(72.0, 140.0))?.abovePct()
        this.lastHourTIRLow100 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0,140.0))?.belowPct()!!
        this.lastHourTIRabove170 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0,170.0))?.abovePct()!!
        this.lastHourTIRabove120 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0,120.0))?.abovePct()!!
        val tirbasal3IR = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.inRangePct()
        val tirbasal3B = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.belowPct()
        val tirbasal3A = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.abovePct()
        val tirbasalhAP = tirCalculator.averageTIR(tirCalculator.calculateHour(65.0, 100.0))?.abovePct()
        //this.enablebasal = preferences.get(BooleanKey.OApsAIMIEnableBasal)
        //this.now = System.currentTimeMillis()
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
        val therapy = Therapy(persistenceLayer).also {
            it.updateStatesBasedOnTherapyEvents()
        }
        val deleteEventDate = therapy.deleteEventDate
        val deleteTime = therapy.deleteTime
        if (deleteTime) {
            //removeLastNLines(100)
            createFilteredAndSortedCopy(deleteEventDate.toString())
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
        this.stable = if (delta>-3 && delta<3 && shortAvgDelta>-3 && shortAvgDelta<3 && longAvgDelta>-3 && longAvgDelta<3 && bg < 180) 1 else 0
         if (isMealModeCondition()){
             val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
                 rT.units = pbolusM
                 rT.reason.append("Microbolusing Meal Mode ${pbolusM}U. ")
             return rT
         }
        if (isMealModeCondition2(variableSensitivity, targetBg, delta, shortAvgDelta, autodrive, mealData.slopeFromMinDeviation, bg.toFloat()) && !mealTime && !highCarbTime && !lunchTime && !bfastTime && !dinnerTime && !snackTime && !sportTime && !snackTime && !lowCarbTime && bgAcceleration.toFloat() >= 2.0f){
            val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
            rT.units = pbolusM
            rT.reason.append("Microbolusing Meal Mode ${pbolusM}U. ")
            return rT
        }
        if (isbfastModeCondition()){
            val pbolusbfast: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
            rT.units = pbolusbfast
            rT.reason.append("Microbolusing 1/2 Breakfast Mode ${pbolusbfast}U. ")
            return rT
        }
        if (isbfast2ModeCondition()){
            val pbolusbfast2: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
            this.maxSMB = pbolusbfast2
            rT.units = pbolusbfast2
            rT.reason.append("Microbolusing 2/2 Breakfast Mode ${pbolusbfast2}U. ")
            return rT
        }
        if (isLunchModeCondition()){
            val pbolusLunch: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
                rT.units = pbolusLunch
                rT.reason.append("Microbolusing 1/2 Lunch Mode ${pbolusLunch}U. ")
            return rT
        }
        if (isLunch2ModeCondition()){
            val pbolusLunch2: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
            this.maxSMB = pbolusLunch2
            rT.units = pbolusLunch2
            rT.reason.append("Microbolusing 2/2 Lunch Mode ${pbolusLunch2}U. ")
            return rT
        }
        if (isDinnerModeCondition()){
            val pbolusDinner: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
            rT.units = pbolusDinner
            rT.reason.append("Microbolusing 1/2 Dinner Mode ${pbolusDinner}U. ")
            return rT
        }
        if (isDinner2ModeCondition()){
            val pbolusDinner2: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
            this.maxSMB = pbolusDinner2
            rT.units = pbolusDinner2
            rT.reason.append("Microbolusing 2/2 Dinner Mode ${pbolusDinner2}U. ")
            return rT
        }
        if (isHighCarbModeCondition()){
            val pbolusHC: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
            rT.units = pbolusHC
            rT.reason.append("Microbolusing High Carb Mode ${pbolusHC}U. ")
            return rT
        }
        if (issnackModeCondition()){
            val pbolussnack: Double = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
            rT.units = pbolussnack
            rT.reason.append("Microbolusing High Carb Mode ${pbolussnack}U. ")
            return rT
        }

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
            !profile.temptargetSet && recentSteps5Minutes >= 0 && (recentSteps30Minutes >= 500 || recentSteps180Minutes > 1500) && recentSteps10Minutes > 0 -> {
                this.targetBg = 130.0f
            }
            !profile.temptargetSet && eventualBG >= 120 && delta > 3 -> {
                var baseTarget = if (honeymoon) 110.0 else 70.0
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
            !profile.temptargetSet && circadianSmb > 0.1 && eventualBG < 110 -> {
                val baseHypoTarget = if (honeymoon) 130.0 else 120.0
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
            !profile.temptargetSet && bg < 110 && delta < 1 -> {
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

            else -> {
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
            iob2 = -iob_data.basaliob.toFloat()+ iob
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
        this.predictedBg = predictEventualBG(bg.toFloat(), iob, variableSensitivity, minDelta.toFloat(), shortAvgDelta, longAvgDelta, mealTime, bfastTime, lunchTime, dinnerTime, highCarbTime, snackTime, honeymoon )
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
        }else{
            this.recentSteps5Minutes = StepService.getRecentStepCount5Min()
            this.recentSteps10Minutes = StepService.getRecentStepCount10Min()
            this.recentSteps15Minutes = StepService.getRecentStepCount15Min()
            this.recentSteps30Minutes = StepService.getRecentStepCount30Min()
            this.recentSteps60Minutes = StepService.getRecentStepCount60Min()
            this.recentSteps180Minutes = StepService.getRecentStepCount180Min()
        }

        try {
            val heartRates5 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis5,now)
            this.averageBeatsPerMinute = heartRates5.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute = 80.0
        }
        try {
            val heartRates10 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis10,now)
            this.averageBeatsPerMinute10 = heartRates10.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute10 = 80.0
        }
        try {
            val heartRates60 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis60,now)
            this.averageBeatsPerMinute60 = heartRates60.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute60 = 80.0
        }
        try {

            val heartRates180 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis180,now)
            this.averageBeatsPerMinute180 = heartRates180.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute180 = 80.0
        }
        if (tdd7Days.toFloat() != 0.0f) {
            basalaimi = (tdd7Days / preferences.get(DoubleKey.OApsAIMIweight)).toFloat()
        }
        this.basalaimi = calculateSmoothBasalRate(tdd7P.toFloat(),tdd7Days.toFloat(),basalaimi)
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
                tirbasalhAP != null && tirbasalhAP >= 5 -> (basalaimi * 2.0).toFloat()
                lastHourTIRAbove != null && lastHourTIRAbove >= 2 -> (basalaimi * 1.8).toFloat()
                timenow < sixAMHour -> {
                    val multiplier = if (honeymoon) 1.2 else 1.4
                    (basalaimi * multiplier).toFloat()
                }
                timenow > sixAMHour -> {
                    val multiplier = if (honeymoon) 1.4 else 1.6
                    (basalaimi * multiplier).toFloat()
                }
                tirbasal3B <= 5 && tirbasal3IR in 70.0..80.0 -> (basalaimi * 1.1).toFloat()
                tirbasal3B <= 5 && tirbasal3IR <= 70 -> (basalaimi * 1.3).toFloat()
                tirbasal3B > 5 && tirbasal3A!! < 5 -> (basalaimi * 0.85).toFloat()
                else -> basalaimi
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
            || recentSteps180Minutes > 1500 && bg < 130 && delta < 10) {

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

        //val expectedDelta = calculateExpectedDelta(target_bg, eventualBG, bgi)
        val modelcal = calculateSMBFromModel()
        // min_bg of 90 -> threshold of 65, 100 -> 70 110 -> 75, and 130 -> 85
        var threshold = min_bg - 0.5 * (min_bg - 40)
        if (profile.lgsThreshold != null) {
            val lgsThreshold = profile.lgsThreshold ?: error("lgsThreshold missing")
            if (lgsThreshold > threshold) {
                consoleError.add("Threshold set from ${convertBG(threshold)} to ${convertBG(lgsThreshold.toDouble())}; ")
                threshold = lgsThreshold.toDouble()
            }
        }
        this.predictedSMB = modelcal

        if (preferences.get(BooleanKey.OApsAIMIMLtraining) && csvfile.exists()){
            val allLines = csvfile.readLines()
            val minutesToConsider = 2500.0
            val linesToConsider = (minutesToConsider / 5).toInt()
            if (allLines.size > linesToConsider) {
                val refinedSMB = neuralnetwork5(combinedDelta.toFloat(), shortAvgDelta, longAvgDelta, predictedSMB, profile)
                this.predictedSMB = refinedSMB
                if (bg > 200 && delta > 4 && iob < preferences.get(DoubleKey.ApsSmbMaxIob) ) {
                    this.predictedSMB *= 1.7f // Augmente de 70% si montée très rapide
                } else if (bg > 180 && delta > 3 && iob < preferences.get(DoubleKey.ApsSmbMaxIob)) {
                    this.predictedSMB *= 1.5f // Augmente de 50% si montée modérée
                }

                basal =
                    when {
                        (honeymoon && bg < 170) -> basalaimi * 0.65
                        else -> basalaimi.toDouble()
                    }
                basal = roundBasal(basal)
            }
            rT.reason.append("csvfile ${csvfile.exists()}")
        }else {
            rT.reason.append("ML Decision data training","ML decision has no enough data to refine the decision")
        }

        var smbToGive = if (bg > 120  && delta > 8 && predictedSMB == 0.0f) modelcal else predictedSMB
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
            bg > 120 && delta > 8 && iob < 1.0 && !honeymoon && smbToGive < 0.05f                                                            -> profile_current_basal.toFloat()
            highCarbTime                                                                                                                     -> smbToGive * highcarbfactor.toFloat()
            mealTime                                                                                                                         -> smbToGive * mealfactor.toFloat()
            bfastTime                                                                                                                        -> smbToGive * bfastfactor.toFloat()
            lunchTime                                                                                                                        -> smbToGive * lunchfactor.toFloat()
            dinnerTime                                                                                                                       -> smbToGive * dinnerfactor.toFloat()
            snackTime                                                                                                                        -> smbToGive * snackfactor.toFloat()
            sleepTime                                                                                                                        -> smbToGive * sleepfactor.toFloat()
            hourOfDay in 1..11                                                                                                         -> smbToGive * adjustedMorningFactor.toFloat()
            hourOfDay in 12..18                                                                                                        -> smbToGive * adjustedAfternoonFactor.toFloat()
            hourOfDay in 19..23                                                                                                        -> smbToGive * adjustedEveningFactor.toFloat()
            bg > 120 && delta > 7 && !honeymoon                                                                                              -> smbToGive * hyperfactor.toFloat()
            bg > 180 && delta > 5 && iob < 1.2 && honeymoon                                                                                  -> smbToGive * hyperfactor.toFloat()
            else -> smbToGive
        }
        rT.reason.append("adjustedMorningFactor $adjustedMorningFactor")
        rT.reason.append("adjustedAfternoonFactor $adjustedAfternoonFactor")
        rT.reason.append("adjustedEveningFactor $adjustedEveningFactor")
        val factors = when {
            lunchTime -> lunchfactor
            bfastTime -> bfastfactor
            dinnerTime -> dinnerfactor
            snackTime -> snackfactor
            sleepTime -> sleepfactor
            hourOfDay in 1..11 -> adjustedMorningFactor
            hourOfDay in 12..18 -> adjustedAfternoonFactor
            hourOfDay in 19..23 -> adjustedEveningFactor
            highCarbTime -> highcarbfactor
            mealTime -> mealfactor
            bg > 120 && delta > 7 && !honeymoon -> hyperfactor
            else -> 1.0
        }


        val actCurr = profile.sensorLagActivity
        val actFuture = profile.futureActivity
        val td = profile.dia * 60
        val deltaGross = round((glucose_status.delta + actCurr * sens).coerceIn(0.0, 35.0), 1)
        val actTarget = deltaGross / sens * factors.toFloat()
        var actMissing = 0.0
        var deltaScore: Double = 0.5

        if (glucose_status.delta <= 4.0) {

            actMissing = round((actCurr * smbToGive - Math.max(actFuture, 0.0)) / 5, 4)
            deltaScore = ((bg - target_bg) / 100).coerceIn(0.0, 1.0)
        } else {
            actMissing = round((actTarget - Math.max(actFuture, 0.0)) / 5, 4)
        }


        val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
        val a = 2 * tau / td
        val S = 1 / (1 - a + (1 + a) * Math.exp((-td / tau)))
        var AimiInsReq = actMissing / (S / Math.pow(tau, 2.0) * tp * (1 - tp / td) * Math.exp((-tp / tau)))

        AimiInsReq = if (AimiInsReq < smbToGive) AimiInsReq else smbToGive.toDouble()

        val finalInsulinDose = round(AimiInsReq, 2)

        smbToGive = applySafetyPrecautions(mealData,finalInsulinDose.toFloat())
        smbToGive = roundToPoint05(smbToGive)

        logDataMLToCsv(predictedSMB, smbToGive)

        //logDataToCsv(predictedSMB, smbToGive)
        //logDataToCsvHB(predictedSMB, smbToGive)

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
        val totalCI = Math.max(0.0, ci / 5 * 60 * remainingCATime / 2)
        // totalCI (mg/dL) / CSF (mg/dL/g) = total carbs absorbed (g)
        val totalCA = totalCI / csf
        val remainingCarbsCap: Int // default to 90
        remainingCarbsCap = min(90, profile.remainingCarbsCap)
        var remainingCarbs = max(0.0, mealData.mealCOB - totalCA)
        remainingCarbs = Math.min(remainingCarbsCap.toDouble(), remainingCarbs)
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
            if (UAMpredBGs.size < 24) UAMpredBGs.add(UAMpredBG!!)
            if (ZTpredBGs.size < 24) ZTpredBGs.add(ZTpredBG)
            // calculate minGuardBGs without a wait from COB, UAM, IOB predBGs
            if (UAMpredBG!! < minUAMGuardBG) minUAMGuardBG = round(UAMpredBG!!).toDouble()
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
            if (enableUAM && UAMpredBGs.size > 6 && (UAMpredBG!! < minUAMPredBG)) minUAMPredBG = round(UAMpredBG!!, 0)
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
        val thresholdBG: Double = 70.0
        val carbsRequired = estimateRequiredCarbs(bg, targetBg.toDouble(), slopeFromDeviations, iob.toDouble(), csf,sens, cob.toDouble())
        val minutesAboveThreshold = calculateMinutesAboveThreshold(bg, slopeFromDeviations, thresholdBG)
        if (carbsRequired >= profile.carbsReqThreshold && minutesAboveThreshold <= 45 && !lunchTime && !dinnerTime && !bfastTime && !highCarbTime && !mealTime) {
            rT.carbsReq = carbsRequired
            rT.carbsReqWithin = minutesAboveThreshold
            rT.reason.append("$carbsRequired add\'l carbs req w/in ${minutesAboveThreshold}m; ")
        }
        var rate = when {
            snackTime && snackrunTime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 4.0, "AI Force basal because snackTime $snackrunTime.", currenttemp, rT)
            mealTime && mealruntime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because mealTime $mealruntime.", currenttemp, rT)
            bfastTime && bfastruntime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because bfastTime $bfastruntime.", currenttemp, rT)
            lunchTime && lunchruntime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because lunchTime $lunchruntime.", currenttemp, rT)
            dinnerTime && dinnerruntime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because dinnerTime $dinnerruntime.", currenttemp, rT)
            highCarbTime && highCarbrunTime in 0..30 && delta < 10 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because highcarb $highcarbfactor.", currenttemp, rT)
            fastingTime -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because fastingTime", currenttemp, rT)
            sportTime && bg > 169 && delta > 4 -> calculateRate(profile_current_basal, profile_current_basal, 1.3, "AI Force basal because sportTime && bg > 170", currenttemp, rT)
            //!honeymoon && delta in 0.0 .. 7.0 && bg in 81.0..111.0 -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because bg lesser than 110 and delta lesser than 8", currenttemp, rT)
            honeymoon && delta in 0.0.. 6.0 && bg in 99.0..141.0 -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because honeymoon and bg lesser than 140 and delta lesser than 6", currenttemp, rT)
            bg in 81.0..99.0 && delta in 3.0..7.0 && honeymoon -> calculateRate(basal, profile_current_basal, 1.0, "AI Force basal because bg is between 80 and 100 with a small delta.", currenttemp, rT)
            //bg > 145 && delta > 0 && smbToGive == 0.0f && !honeymoon -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because bg is greater than 145 and SMB = 0U.", currenttemp, rT)
            bg > 120 && delta > 0 && smbToGive == 0.0f && honeymoon -> calculateRate(basal, profile_current_basal, 5.0, "AI Force basal because bg is greater than 120 and SMB = 0U.", currenttemp, rT)
            else -> null
        }
        rate?.let {
            rT.rate = it
            rT.deliverAt = deliverAt
            rT.duration = 30
            return rT
        }

        val enableSMB = enablesmb(profile, microBolusAllowed, mealData, target_bg)


        rT.COB = mealData.mealCOB
        rT.IOB = iob_data.iob
        rT.reason.append(
            "COB: ${round(mealData.mealCOB, 1).withoutZeros()}, Dev: ${convertBG(deviation.toDouble())}, BGI: ${convertBG(bgi)}, ISF: ${convertBG(sens)}, CR: ${
                round(profile.carb_ratio, 2)
                    .withoutZeros()
            }, Target: ${convertBG(target_bg)}}"
        )

         val (conditionResult, conditionsTrue) = isCriticalSafetyCondition(mealData)

        val screenWidth = preferences.get(IntKey.OApsAIMIlogsize)// Largeur d'écran par défaut en caractères si non spécifié
        val columnWidth = (screenWidth / 2) - 2 // Calcul de la largeur des colonnes en fonction de la largeur de l'écran

        val logTemplate = buildString {
            appendLine("╔${"═".repeat(screenWidth)}╗")
            appendLine(String.format("║ %-${screenWidth}s ║", "AAPS-MASTER-AIMI"))
            appendLine(String.format("║ %-${screenWidth}s ║", "OpenApsAIMI Settings"))
            appendLine(String.format("║ %-${screenWidth}s ║", "08 Mars 2025"))
            appendLine("╚${"═".repeat(screenWidth)}╝")
            appendLine()

            appendLine("╔${"═".repeat(screenWidth)}╗")
            appendLine(String.format("║ %-${screenWidth}s ║", "Request"))
            appendLine("╠${"═".repeat(screenWidth)}╣")
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Reason", "COB: $cob, Dev: $deviation, BGI: $bgi, ISF: $variableSensitivity, CR: $ci, Target: $target_bg"))
            appendLine("╚${"═".repeat(screenWidth)}╝")
            appendLine()

            appendLine("╔${"═".repeat(screenWidth)}╗")
            appendLine(String.format("║ %-${screenWidth}s ║", "SMB Prediction"))
            appendLine("╠${"═".repeat(screenWidth)}╣")
            appendLine(String.format("║ %-${columnWidth}s │ %s u", "AI Pred.", String.format("%.2f", predictedSMB)))
            appendLine(String.format("║ %-${columnWidth}s │ %s u", "Req. SMB", String.format("%.2f", smbToGive)))
            appendLine("╚${"═".repeat(screenWidth)}╝")
            appendLine()

            appendLine("╔${"═".repeat(screenWidth)}╗")
            appendLine(String.format("║ %-${screenWidth}s ║", "Adjusted Factors"))
            appendLine("╠${"═".repeat(screenWidth)}╣")
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Factors", adjustedFactors))
            appendLine("╚${"═".repeat(screenWidth)}╝")
            appendLine()

            appendLine("╔${"═".repeat(screenWidth)}╗")
            appendLine(String.format("║ %-${screenWidth}s ║", "Limits & Conditions"))
            appendLine("╠${"═".repeat(screenWidth)}╣")
            appendLine(String.format("║ %-${columnWidth}s │ %s u", "Max IOB", String.format("%.1f", maxIob)))
            appendLine(String.format("║ %-${columnWidth}s │ %s u", "IOB", String.format("%.1f", iob)))
            appendLine(String.format("║ %-${columnWidth}s │ %s u", "IOB2", String.format("%.1f", iob2)))
            appendLine(String.format("║ %-${columnWidth}s │ %s u", "Max SMB", String.format("%.1f", maxSMB)))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Safety", conditionResult))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Met", conditionsTrue))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "peakTimeProfile", String.format("%.1f", profile.peakTime)))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "currentActivity", String.format("%.1f", profile.currentActivity)))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "After IOB Adjustment", String.format("%.1f", peakintermediaire)))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Activity Ratio", String.format("%.1f", profile.futureActivity / (profile.currentActivity + 0.0001))))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Final Peak Time after coerceIn", String.format("%.1f", tp)))
            appendLine("╚${"═".repeat(screenWidth)}╝")
            appendLine()

            appendLine("╔${"═".repeat(screenWidth)}╗")
            appendLine(String.format("║ %-${screenWidth}s ║", "Glucose Data"))
            appendLine("╠${"═".repeat(screenWidth)}╣")
            appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "Current BG", String.format("%.1f", bg)))
            appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "Target BG", String.format("%.1f", targetBg)))
            appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "Prediction", String.format("%.1f", predictedBg)))
            appendLine(String.format("║ %-${columnWidth}s │ %s mg/dL", "Eventual BG", String.format("%.1f", eventualBG)))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Delta", String.format("%.1f", delta)))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Short Δ", String.format("%.1f", shortAvgDelta)))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Long Δ", String.format("%.1f", longAvgDelta)))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "slopeFromMaxDeviation", String.format("%.1f", mealData.slopeFromMaxDeviation)))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "slopeFromMinDeviation", String.format("%.1f", mealData.slopeFromMinDeviation)))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "bgAcceleration", String.format("%.1f", bgAcceleration)))
            appendLine("╚${"═".repeat(screenWidth)}╝")
            appendLine()

            appendLine("╔${"═".repeat(screenWidth)}╗")
            appendLine(String.format("║ %-${screenWidth}s ║", "TIR Data"))
            appendLine("╠${"═".repeat(screenWidth)}╣")
            appendLine(String.format("║ %-${columnWidth}s │ %s%%", "TIR Low", String.format("%.1f", currentTIRLow)))
            appendLine(String.format("║ %-${columnWidth}s │ %s%%", "TIR In Range", String.format("%.1f", currentTIRRange)))
            appendLine(String.format("║ %-${columnWidth}s │ %s%%", "TIR High", String.format("%.1f", currentTIRAbove)))
            appendLine(String.format("║ %-${columnWidth}s │ %s%%", "Last Hr TIR Low", String.format("%.1f", lastHourTIRLow)))
            appendLine(String.format("║ %-${columnWidth}s │ %s%%", "Last Hr TIR >120", String.format("%.1f", lastHourTIRabove120)))
            appendLine("╚${"═".repeat(screenWidth)}╝")
            appendLine()

            appendLine("╔${"═".repeat(screenWidth)}╗")
            appendLine(String.format("║ %-${screenWidth}s ║", "Step Data"))
            appendLine("╠${"═".repeat(screenWidth)}╣")
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Steps (5m)", recentSteps5Minutes))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Steps (30m)", recentSteps30Minutes))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Steps (60m)", recentSteps60Minutes))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Steps (180m)", recentSteps180Minutes))
            appendLine("╚${"═".repeat(screenWidth)}╝")
            appendLine()

            appendLine("╔${"═".repeat(screenWidth)}╗")
            appendLine(String.format("║ %-${screenWidth}s ║", "Heart Rate Data"))
            appendLine("╠${"═".repeat(screenWidth)}╣")
            appendLine(String.format("║ %-${columnWidth}s │ %s bpm", "HR (5m)", String.format("%.1f", averageBeatsPerMinute)))
            appendLine(String.format("║ %-${columnWidth}s │ %s bpm", "HR (60m)", String.format("%.1f", averageBeatsPerMinute60)))
            appendLine("╚${"═".repeat(screenWidth)}╝")
            appendLine()

            appendLine("╔${"═".repeat(screenWidth)}╗")
            appendLine(String.format("║ %-${screenWidth}s ║", "Modes"))
            appendLine("╠${"═".repeat(screenWidth)}╣")
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Delete Time", if (deleteTime) "Active" else "Inactive"))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Date", deleteEventDate ?: "N/A"))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Sleep", if (sleepTime) "Active" else "Inactive"))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Sport", if (sportTime) "Active" else "Inactive"))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Snack", if (snackTime) "Active" else "Inactive"))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Low Carb", if (lowCarbTime) "Active" else "Inactive"))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "High Carb", if (highCarbTime) "Active" else "Inactive"))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Meal", if (mealTime) "Active" else "Inactive"))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Breakfast", if (bfastTime) "Active" else "Inactive"))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Lunch", if (lunchTime) "Active" else "Inactive"))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Dinner", if (dinnerTime) "Active" else "Inactive"))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Fasting", if (fastingTime) "Active" else "Inactive"))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Calibration", if (iscalibration) "Active" else "Inactive"))
            appendLine("╚${"═".repeat(screenWidth)}╝")
            appendLine()

            appendLine("╔${"═".repeat(screenWidth)}╗")
            appendLine(String.format("║ %-${screenWidth}s ║", "Miscellaneous"))
            appendLine("╠${"═".repeat(screenWidth)}╣")
            appendLine(String.format("║ %-${columnWidth}s │ %s min", "Last SMB", lastsmbtime))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Hour", hourOfDay))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "Weekend", weekend))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "tags0-60m", tags0to60minAgo))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "tags60-120m", tags60to120minAgo))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "tags120-180m", tags120to180minAgo))
            appendLine(String.format("║ %-${columnWidth}s │ %s", "tags180-240m", tags180to240minAgo))
            appendLine("╚${"═".repeat(screenWidth)}╝")
            appendLine()

            // Fin de l'assemblage du log
        }

        rT.reason.append(logTemplate)

        // eventual BG is at/above target
        // if iob is over max, just cancel any temps
        if (eventualBG >= max_bg) {
            rT.reason.append("Eventual BG " + convertBG(eventualBG) + " >= " + convertBG(max_bg) + ", ")
        }
        if (iob_data.iob > max_iob) {
            rT.reason.append("IOB ${round(iob_data.iob, 2)} > max_iob $max_iob")
            if (delta < 0) {
                rT.reason.append(", BG is dropping (delta $delta), setting basal to 0. ")
                return setTempBasal(0.0, 30, profile, rT, currenttemp) // Basal à 0 pendant 30 minutes
            }
            return if (currenttemp.duration > 15 && (roundBasal(basal) == roundBasal(currenttemp.rate))) {
                rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                rT
            } else {
                rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                setTempBasal(basal, 30, profile, rT, currenttemp)
            }
        } else {
            var insulinReq = smbToGive.toDouble()
            val recentBGValues: List<Float> = getRecentBGs()
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
                targetBG = targetBg
            )
            rT.reason.append(safetyDecision.reason)

            // Ajustement de la dose SMB proposée en fonction du bolusFactor calculé
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
                val SMBInterval = min(20, max(1, calculateSMBInterval()))
                val nextBolusMins = round(SMBInterval - lastBolusAge, 0)
                val nextBolusSeconds = round((SMBInterval - lastBolusAge) * 60, 0) % 60
                if (lastBolusAge > SMBInterval) {
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
            rate = profile_current_basal
            if (safetyDecision.stopBasal) {
                return setTempBasal(0.0, 30, profile, rT, currenttemp)
            }
            if (detectMealOnset(delta, predicted.toFloat(), bgAcceleration.toFloat()) && !mealTime && !lunchTime && !bfastTime && !dinnerTime && !sportTime && !snackTime && !highCarbTime && !sleepTime && !lowCarbTime) {
                rT.reason.append("Détection précoce de repas: activation d'une basale maximale pendant 30 minutes. ")
                val forcedBasal = profile_current_basal * 10  // Exemple, ajuster le facteur selon le profil
                return setTempBasal(forcedBasal, 30, profile, rT, currenttemp)
            }
            // 🔴 Sécurité : Arrêt de la basale en cas de tendance baissière ou IOB trop élevé
            if (bg < 110 && mealData.slopeFromMaxDeviation <= 0 || iob > maxIob) {
                return setTempBasal(0.0, 30, profile, rT, currenttemp)
            }

            // ⚠️ Gestion des hypoglycémies et basale réduite si risque
            when {
                bg < 80                                                                                                                  -> rate = 0.0
                bg in 80.0..90.0 && slopeFromMaxDeviation <= 0 && iob > 0.1 && !sportTime                                                -> rate = 0.0
                bg in 80.0..90.0 && slopeFromMinDeviation >= 0.3 && slopeFromMaxDeviation >= 0 &&
                    combinedDelta in -1.0..2.0 && !sportTime && bgAcceleration.toFloat() > 0.0f                                                  -> rate = profile_current_basal * 0.2

                bg in 90.0..100.0 && slopeFromMinDeviation <= 0.3 && iob > 0.1 && !sportTime && bgAcceleration.toFloat() > 0.0f          -> rate = 0.0
                bg in 90.0..100.0 && slopeFromMinDeviation >= 0.3 && combinedDelta in -1.0..2.0 && !sportTime && bgAcceleration.toFloat() > 0.0f -> rate = profile_current_basal * 0.5
            }

            // 🔺 Gestion des hausses lentes et rapides
            if (bg > 120 && slopeFromMinDeviation in 0.4..20.0 && combinedDelta > 1 && !sportTime && bgAcceleration.toFloat() > 1.0f) {
                rate = calculateBasalRate(finalBasalRate, profile_current_basal, combinedDelta.toDouble())
            } else if (eventualBG > 110 && !sportTime && bg > 150 && combinedDelta in -2.0..15.0 && bgAcceleration.toFloat() > 0.0f) {
                rate = calculateBasalRate(finalBasalRate, profile_current_basal, basalAdjustmentFactor)
            }

            // 🔵 Gestion des horaires et activité
            if ((timenow in 11..13 || timenow in 18..21) && iob < 0.8 && recentSteps5Minutes < 100 && combinedDelta > -1 && slopeFromMinDeviation > 0.3 && bgAcceleration.toFloat() > 0.0f) {
                rate = profile_current_basal * 1.5
            } else if (timenow > sixAMHour && recentSteps5Minutes > 100) {
                rate = 0.0
            } else if (timenow <= sixAMHour && delta > 0 && bgAcceleration.toFloat() > 0.0f) {
                rate = profile_current_basal
            }

            // 🍽️ Gestion des repas et snacks
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
                    rate = calculateBasalRate(finalBasalRate, profile_current_basal, 10.0)
                } else if (meal && runtime in 30..60 && delta > 0) {
                    rate = calculateBasalRate(finalBasalRate, profile_current_basal, delta.toDouble())
                }
            }

            // 🟢 Gestion des hyperglycémies et corrections
            when {
                eventualBG > 180 && delta > 3  -> rate = calculateBasalRate(basalaimi.toDouble(), profile_current_basal, basalAdjustmentFactor)
                bg > 180 && delta in -5.0..1.0 -> rate = profile_current_basal * basalAdjustmentFactor
            }

            // 🌙 Mode honeymoon
            if (honeymoon) {
                when {
                    bg in 140.0..169.0 && delta > 0                                                                                             -> rate = profile_current_basal
                    bg > 170 && delta > 0                                                                                                       -> rate = calculateBasalRate(finalBasalRate, profile_current_basal, basalAdjustmentFactor)
                    combinedDelta > 2 && bg in 90.0..119.0                                                                                              -> rate = profile_current_basal
                    combinedDelta > 0 && bg > 110 && eventualBG > 120 && bg < 160                                                                       -> rate = profile_current_basal * basalAdjustmentFactor
                    mealData.slopeFromMaxDeviation > 0 && mealData.slopeFromMinDeviation > 0 && bg > 110 && combinedDelta > 0                           -> rate = profile_current_basal * basalAdjustmentFactor
                    mealData.slopeFromMaxDeviation in 0.0..0.2 && mealData.slopeFromMinDeviation in 0.0..0.5 && bg in 120.0..150.0 && delta > 0 -> rate = profile_current_basal * basalAdjustmentFactor
                    mealData.slopeFromMaxDeviation > 0 && mealData.slopeFromMinDeviation > 0 && bg in 100.0..120.0 && delta > 0                 -> rate = profile_current_basal * basalAdjustmentFactor
                }
            }

            // 🤰 Cas de grossesse
            if (pregnancyEnable && delta > 0 && bg > 110 && !honeymoon) {
                rate = calculateBasalRate(finalBasalRate, profile_current_basal, basalAdjustmentFactor)
            }

// Application finale
            rate.let {
                rT.rate = it
                if (rate != null) {
                    rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} AI Force basal because of specific condition: ${round(rate.toDouble(), 2)}U/hr. ")
                }
                return setTempBasal(rate!!, 30, profile, rT, currenttemp)
            }

        }
    }
}
