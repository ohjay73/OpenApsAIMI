package app.aaps.plugins.aps.openAPSAIMI

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.LongSparseArray
import androidx.annotation.ArrayRes
import androidx.core.util.forEach
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.R as CoreKeysR
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.keys.interfaces.withEntries
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.ui.compose.icons.IcPluginOpenAPS
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPS.TddStatus
import app.aaps.plugins.aps.openAPSAIMI.ISF.IsfAdjustmentEngine
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.floor
import app.aaps.plugins.aps.openAPSAIMI.ISF.IsfBlender
import app.aaps.plugins.aps.openAPSAIMI.pkpd.IsfFusion
import app.aaps.plugins.aps.openAPSAIMI.pkpd.IsfFusionBounds
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration
import androidx.core.util.isEmpty
import androidx.core.util.size
import androidx.core.net.toUri
import kotlin.math.abs
import kotlin.math.exp
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiPkpdSettingsScreen
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiBackupManager
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

@Singleton
open class OpenAPSAIMIPlugin  @Inject constructor(
    private val injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    private val preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val glucoseStatusCalculatorAimi: GlucoseStatusCalculatorAimi,
    private val tddCalculator: TddCalculator,
    private val bgQualityCheck: BgQualityCheck,
    private val uiInteraction: UiInteraction,
    private val determineBasalaimiSMB2: DetermineBasalaimiSMB2,
    private val profiler: Profiler,
    private val context: Context,
    private val apsResultProvider: Provider<APSResult>,
    private val unifiedReactivityLearner: app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner, // 🧠 Brain Injection
    private val stepsManager: app.aaps.plugins.aps.openAPSAIMI.steps.AIMIStepsManagerMTR, // 🏃 Steps Manager MTR
    private val physioManager: app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioManagerMTR, // 🏥 Physiological Manager MTR
    // 🏥 Physiological Decision Adapter (The Safety Gate)
    private val physioAdapter: app.aaps.plugins.aps.openAPSAIMI.physio.AIMIInsulinDecisionAdapterMTR,
    private val auditorOrchestrator: app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorOrchestrator, // 🧠 AI Auditor MTR
    private val contextManager: app.aaps.plugins.aps.openAPSAIMI.context.ContextManager, // 🎯 Context Manager
    private val aimiBackupManager: AimiBackupManager, // ☁️ Cloud Backup Manager (Force Init)
    private val insulin: Insulin,
    private val ch: ConcentrationHelper,
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .composeContent { plugin ->
            app.aaps.plugins.aps.compose.OpenAPSComposeContent(
                apsPlugin = plugin as APS,
                rxBus = rxBus,
                rh = rh,
                dateUtil = dateUtil
            )
        }
        .icon(IcPluginOpenAPS)
        .pluginName(R.string.openapsaimi)
        .shortName(R.string.oaps_aimi_shortname)
        .preferencesVisibleInSimpleMode(false)
        .showInList({ config.APS })
        .description(R.string.description_openapsaimi)
        .setDefault(),
    aapsLogger, rh
), APS, PluginConstraints {

    override fun onStart() {
        super.onStart()
        preferences.registerPreferences(app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey::class.java)
        preferences.registerPreferences(app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey::class.java)

        // 🏃 Start AIMI Steps Manager (Health Connect + Phone Sensor sync)
        try {
            stepsManager.start()
            aapsLogger.info(LTag.APS, "✅ AIMI Steps Manager started successfully")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "❌ Failed to start AIMI Steps Manager", e)
        }
        
        // 🏥 Start AIMI Physiological Manager
        try {
            physioManager.start()
            aapsLogger.info(LTag.APS, "✅ AIMI Physiological Manager started successfully")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "❌ Failed to start AIMI Physiological Manager", e)
        }

        physioPreferenceDisposable?.dispose()
        physioPreferenceDisposable = rxBus.toObservable(EventPreferenceChange::class.java).subscribe(
            { event ->
                if (!event.isChanged(BooleanKey.AimiPhysioAssistantEnable.key)) return@subscribe
                try {
                    if (preferences.get(BooleanKey.AimiPhysioAssistantEnable)) {
                        physioManager.start()
                    } else {
                        physioManager.stop()
                    }
                } catch (e: Exception) {
                    aapsLogger.error(LTag.APS, "Physio preference toggle handling failed", e)
                }
            },
            { t -> aapsLogger.error(LTag.APS, "Physio preference Rx error", t) }
        )
        
        // 🧠 Start AIMI Neural Trainer
        try {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build()
                
            val workRequest = androidx.work.PeriodicWorkRequestBuilder<app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveNeuralTrainerWorker>(
                6, java.util.concurrent.TimeUnit.HOURS
            ).setConstraints(constraints).build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "AIMINeuralTrainer",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            aapsLogger.info(LTag.APS, "✅ AIMI Neural Trainer scheduled successfully")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "❌ Failed to schedule AIMI Neural Trainer", e)
        }
        
        AimiUamHandler.clearCache(context)
        AimiUamHandler.installConfidenceSupplier {
            // retourne null si tu veux "laisser la main" au runtime
            preferences.get(DoubleKey.AimiUamConfidence)
        }
        var count = 0
        val apsResults = runBlocking {
            persistenceLayer.getApsResults(dateUtil.now() - T.days(1).msecs(), dateUtil.now())
        }
        apsResults.forEach {
            val glucose = it.glucoseStatus?.glucose ?: return@forEach
            val variableSens = it.variableSens ?: return@forEach
            val timestamp = it.date
            val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
            if (variableSens > 0) dynIsfCache.put(key, variableSens)
            count++
        }
        aapsLogger.debug(LTag.APS, "Loaded $count variable sensitivity values from database")

        // 🧠 Pre-load ML model into memory for O(1) SMB inference on hot path
        try {
            val externalDir = java.io.File(android.os.Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
            app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbTrainer.loadModel(externalDir)
            aapsLogger.info(LTag.APS, "✅ AimiSmbTrainer: model load requested (async)")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "❌ AimiSmbTrainer: failed to request model load", e)
        }
    }
    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        glucoseStatusCalculatorAimi.getGlucoseStatusData(allowOldData)
    override fun onStop() {
        super.onStop()

        physioPreferenceDisposable?.dispose()
        physioPreferenceDisposable = null
        
        // 🏃 Stop AIMI Steps Manager
        try {
            stepsManager.stop()
            aapsLogger.info(LTag.APS, "🛑 AIMI Steps Manager stopped")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Error stopping AIMI Steps Manager", e)
        }
        
        // 🏥 Stop AIMI Physiological Manager
        try {
            physioManager.stop()
            aapsLogger.info(LTag.APS, "🛑 AIMI Physiological Manager stopped")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Error stopping AIMI Physiological Manager", e)
        }

        try {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("AIMINeuralTrainer")
            aapsLogger.info(LTag.APS, "🛑 AIMI Neural Trainer stopped")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Error stopping AIMI Neural Trainer", e)
        }

        AimiUamHandler.close(context)
    }
    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.AIMI
    override var lastAPSResult: APSResult? = null
    override fun supportsDynamicIsf(): Boolean = preferences.get(BooleanKey.ApsUseDynamicSensitivity)
    private val pkpdIntegration = PkPdIntegration(preferences)
    private var lastPkpdScale: Double = 1.0
    // Dans votre classe principale (ou plugin), vous pouvez déclarer :
    private val kalmanISFCalculator = KalmanISFCalculator(tddCalculator, preferences, aapsLogger)
    // Fusion lente (TDD/profile) + rate-limit de blend
    private val isfBlender = IsfBlender()
    // top-level (à côté de isfBlender / pkpdIntegration)
    private val isfAdjEngine = IsfAdjustmentEngine()

    /** Réagit au switch Physio sans redémarrer l’app (planifie / annule WorkManager). */
    private var physioPreferenceDisposable: Disposable? = null

    // état EMA persistant (clé Prefs à créer si tu veux le garder entre runs)
    private var tddEma: Double? = null
    private val TDD_EMA_ALPHA = 0.2 // ou pref


    // Recrée les bornes de la fusion ISF depuis les préférences (mêmes clés que PkPdIntegration)
    private fun isfFusion(): IsfFusion {
        val bounds = IsfFusionBounds(
            minFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor),
            maxFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor),
            maxChangePer5Min = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick)
        )
        return IsfFusion(bounds)
    }

    @SuppressLint("DefaultLocale")
    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        val start = dateUtil.now()
        val multiplier = (profile as? ProfileSealed.EPS)?.value?.originalPercentage?.div(100.0)
            ?: return null

        val sensitivity = runBlocking { calculateVariableIsf(start) }

        profiler.log(
            LTag.APS,
            "getIsfMgdl() ${sensitivity.first} ${sensitivity.second} ${dateUtil.dateAndTimeAndSecondsString(start)} $caller",
            start
        )

        return sensitivity.second?.let { it * multiplier }
    }

    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? {
        if (dynIsfCache.isEmpty()) {
            aapsLogger.warn(LTag.APS, "dynIsfCache is empty. Unable to calculate average ISF.")
            return runBlocking { profileFunction.getProfile()?.getProfileIsfMgdl() } ?: 20.0
        }
        var count = 0
        var sum = 0.0
        val start = timestamp - T.hours(24).msecs()
        dynIsfCache.forEach { key, value ->
            if (key in start..timestamp) {
                count++
                sum += value
            }
        }
        val sensitivity = if (count == 0) runBlocking { profileFunction.getProfile()?.getProfileIsfMgdl() } else sum / count
        aapsLogger.debug(LTag.APS, "getAverageIsfMgdl() $sensitivity from $count values ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $caller")
        return sensitivity
    }

    override fun specialEnableCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (ignored: Exception) {
            // may fail during initialization
            true
        }
    }

    override fun specialShowInListCondition(): Boolean {
        val pump = activePlugin.activePump
        return pump.pumpDescription.isTempBasalCapable
    }

    private val dynIsfCache = LongSparseArray<Double>()

    // Exemple de fonction pour prédire le delta futur à partir d'un historique récent
    private fun predictedDelta(deltaHistory: List<Double>): Double {
        if (deltaHistory.isEmpty()) return 0.0
        // Par exemple, on peut utiliser une moyenne pondérée avec des poids croissants pour donner plus d'importance aux valeurs récentes
        val weights = (1..deltaHistory.size).map { it.toDouble() }
        val weightedSum = deltaHistory.zip(weights).sumOf { it.first * it.second }
        return weightedSum / weights.sum()
    }
    private fun estimateKalmanTrustFromDelta(delta: Double?): Double {
        val d = kotlin.math.abs(delta ?: 0.0)
        // 0..10 mg/dL/5min -> 0.1..0.9
        return (d / 10.0).coerceIn(0.1, 0.9)
    }

    // ISF basé TDD (ancre 1800/TDD 24h) avec garde-fous
    private suspend fun tddIsf24hOr(profileIsf: Double): Double {
        val tdd24 = tddCalculator
            .averageTDD(tddCalculator.calculate(1, allowMissingDays = false))
            ?.data?.totalAmount
            ?: preferences.get(DoubleKey.OApsAIMITDD7) // fallback 7j
        val anchored = if (tdd24 > 0.1) 1800.0 / tdd24 else profileIsf
        return anchored.coerceIn(5.0, 400.0)
    }
    private fun dynamicDeltaCorrectionFactor(delta: Double?, predicted: Double?, bg: Double?): Double {
        if (delta == null || predicted == null || bg == null) return 1.0
        val combinedDelta = (delta + predicted) / 2.0
        return when {
            // En cas d'hypoglycémie (delta négatif), on augmente progressivement l'ISF
            combinedDelta < 0 -> {
                val factor = exp(0.15 * abs(combinedDelta))
                factor.coerceAtMost(1.4)
            }
            // En hyperglycémie : si BG est > 130, on applique une réduction progressive
            bg > 110.0        -> {
                // On réduit d’un certain pourcentage (ici jusqu’à 30%) en fonction de BG
                val bgReduction = 1.0 - ((bg - 110.0) / (200.0 - 110.0)) * 0.5
                // On combine ce facteur avec la réponse exponentielle basée sur combinedDelta si nécessaire
                if (combinedDelta > 10) {
                    // Si le delta est important, on accentue la réduction avec une réponse exponentielle
                    val expFactor = exp(-0.3 * (combinedDelta - 10))
                    minOf(expFactor, bgReduction)
                } else {
                    bgReduction
                }
            }

            else              -> 1.0
        }
    }

    private fun getRecentDeltas(): List<Double> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        val smb = glucoseStatusCalculatorAimi.getGlucoseStatusData(true) ?: return emptyList()
        val bg = smb.glucose
        val deltaNow = smb.delta
        if (data.isEmpty()) return emptyList()

        val standardWindow = if (bg < 130) 30f else 15f
        val rapidRiseWindow = 10f
        val intervalMinutes = if (deltaNow > 15) rapidRiseWindow else standardWindow

        val nowTs = data.first().timestamp
        val recent = mutableListOf<Double>()
        for (i in 1 until data.size) {
            val r = data[i]
            if (r.value > 39 && !r.filledGap) {
                val minAgo = ((nowTs - r.timestamp) / 60000.0).toFloat()
                if (minAgo in 0.0f..intervalMinutes) {
                    val d = (data.first().recalculated - r.recalculated) / minAgo * 5f
                    recent.add(d)
                }
            }
        }
        return recent
    }
    @SuppressLint("DefaultLocale")
    private suspend fun calculateVariableIsf(timestamp: Long): Pair<String, Double?> {
        if (!preferences.get(BooleanKey.ApsUseDynamicSensitivity)) return "OFF" to null

        // 0) cache DB existant
        val result = persistenceLayer.getApsResultCloseTo(timestamp)
        if (result?.variableSens != null) return "DB" to result.variableSens

        // 1) BG & deltas actuels
        val glucose = glucoseStatusProvider.glucoseStatusData?.glucose ?: return "GLUC" to null
        val currentDelta = glucoseStatusProvider.glucoseStatusData?.delta
        val recentDeltas = getRecentDeltas()
        val predictedDelta = predictedDelta(recentDeltas)

        // 2) facteur historique (comme avant)
        val dynamicFactor = dynamicDeltaCorrectionFactor(currentDelta, predictedDelta, glucose)

        // 3) ISF rapide #1 : Kalman existant
        val kalmanFastIsf = kalmanISFCalculator.calculateISF(glucose, currentDelta, predictedDelta)
        aapsLogger.debug(LTag.APS, "Adaptive ISF via Kalman: $kalmanFastIsf for BG: $glucose")

        // 4) ISF lent (socle) : profil/TDD fusionné + pkpdScale (inchangé)
        val profileIsf = profileFunction.getProfile()?.getProfileIsfMgdl() ?: 20.0
        val tddIsf = tddIsf24hOr(profileIsf)
        val fusedSlowIsf = isfFusion().fused(profileIsf, tddIsf, lastPkpdScale)
        aapsLogger.debug(LTag.APS, "Fused slow ISF: $fusedSlowIsf (profile=$profileIsf, tddIsf=$tddIsf, pkpdScale=$lastPkpdScale)")

        // 5) EMA TDD (stabilise l’ajustement AF)
        val tdd24 = tddCalculator.calculateDaily(-24, 0)?.totalAmount ?: tddIsf /* fallback */
        tddEma = when (val prev = tddEma) {
            null -> tdd24
            else -> prev + TDD_EMA_ALPHA * (tdd24 - prev)
        }

        // 6) proxys de confiance (si variance non exposée ici)
        val kalmanTrustProxy = estimateKalmanTrustFromDelta(currentDelta)             // 0..1
        val kalmanVarProxy = (1.0 - kalmanTrustProxy).coerceIn(0.0, 1.0)             // 1-trust
        val sippConfidence = AimiUamHandler.confidenceOrZero().coerceIn(0.0, 1.0)

        // 7) ISF rapide #2 : IsfAdjustmentEngine (AF ln(BG/55) + TDD-EMA + rate-limit)
        val isfAdj = isfAdjEngine.compute(
            bgKalman = glucose,
            tddEma   = (tddEma ?: tdd24),
            profileIsf = profileIsf,
            sippConfidence = sippConfidence,
            kalmanVar = kalmanVarProxy,
            nowMs = System.currentTimeMillis()
        )
        aapsLogger.debug(LTag.APS, "Adaptive ISF via IsfAdjustmentEngine: $isfAdj (tddEma=$tddEma, sipp=$sippConfidence, var=$kalmanVarProxy)")

        // 8) Combine les deux rapides par médiane robuste (résistant aux outliers)
        val fastMedian = listOf(kalmanFastIsf, isfAdj).sorted()[1]

        // 9) Blend final (socle lent vs rapide), avec rate-limit temporel de IsfBlender
        var blended = isfBlender.blend(
            fusedIsf = fusedSlowIsf,
            kalmanIsf = fastMedian,
            trustFast = kalmanTrustProxy,
            nowMs = System.currentTimeMillis()
        )

        // 10) facteur dynamique + bornes globales
        blended *= dynamicFactor
        
        // 🏥 PHYSIO MODULATION (ISF)
        // We fetching multipliers for the specific timestamp is tricky, so we use current context
        // This affects the "Displayed ISF" in AAPS.
        
        // Calculate IOB/COB for Cosine Gate
        val profile = profileFunction.getProfile()
        if (profile != null) {
            val iobCalc = iobCobCalculator.calculateFromTreatmentsAndTemps(timestamp, profile)
            val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
            
            val physioMults = physioAdapter.getMultipliers(
                currentBG = glucose, 
                currentDelta = currentDelta ?: 0.0,
                iob = iobCalc.iob,
                cob = mealData.carbs
            )
            if (physioMults.isfFactor != 1.0) {
                blended *= physioMults.isfFactor
                aapsLogger.debug(LTag.APS, "🏥 DynISF modulated by Physio: x${physioMults.isfFactor} -> $blended")
            }
        }

        blended = blended.coerceIn(5.0, 300.0)

        aapsLogger.debug(LTag.APS, "Final DynISF: $blended")
        aapsLogger.debug(
            LTag.APS,
            "DynISF inputs: fusedSlowIsf=$fusedSlowIsf, kalmanFastIsf=$kalmanFastIsf, isfAdj=$isfAdj, trustFast=$kalmanTrustProxy, pkpdScale=$lastPkpdScale"
        )

        // 11) cache
        val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
        if (dynIsfCache.size > 1000) dynIsfCache.clear()
        dynIsfCache.put(key, blended)

        return "CALC" to blended
    }


    override suspend fun invoke(initiator: String, tempBasalFallback: Boolean): Unit = withContext(Dispatchers.Default) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = getGlucoseStatusData(false)
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
            return@withContext
        }
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump

        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
            return@withContext
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_disabled))
            return@withContext
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

        val eff = profile as EffectiveProfile
        if (!hardLimits.checkHardLimits(eff.iCfg.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return@withContext
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.minIC(),
                hardLimits.maxIC()
            )
        ) return@withContext
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSAIMIPlugin"), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return@withContext
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return@withContext
        if (!hardLimits.checkHardLimits(ch.fromPump(pump.baseBasalRate), app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return@withContext

        // End of check, start gathering data
        
        // 🏥 PHYSIO INTEGRATION: Retrieve Context & Multipliers
        val glucoseForPhysio = glucoseStatusProvider.glucoseStatusData?.glucose ?: 100.0
        val deltaForPhysio = glucoseStatusProvider.glucoseStatusData?.delta ?: 0.0
        
        // Calculate IOB/COB early for Physio Adapter
        val nowMs = dateUtil.now()
        val iobCalc = iobCobCalculator.calculateFromTreatmentsAndTemps(nowMs, profile) // Uses 'profile' from enabled check above
        val mealDataForPhysio = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
        
        val physioMults = physioAdapter.getMultipliers(
            currentBG = glucoseForPhysio, 
            currentDelta = deltaForPhysio,
            iob = iobCalc.iob,
            cob = mealDataForPhysio.carbs
        )
        
        if (!physioMults.isNeutral()) {
            aapsLogger.info(LTag.APS, "🏥 LOOP: Applying Physio Factors: ISF x${physioMults.isfFactor}, Basal x${physioMults.basalFactor}, SMB x${physioMults.smbFactor}")
        }

        val dynIsfMode = preferences.get(BooleanKey.ApsUseDynamicSensitivity)
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val advancedFiltering = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )
        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), app.aaps.core.ui.R.string.profile_low_target, HardLimits.LIMIT_MIN_BG[0], HardLimits.LIMIT_MIN_BG[1])
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), app.aaps.core.ui.R.string.profile_high_target, HardLimits.LIMIT_MAX_BG[0], HardLimits.LIMIT_MAX_BG[1])
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG[0], HardLimits.LIMIT_TARGET_BG[1])
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, app.aaps.core.ui.R.string.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG[0], HardLimits.LIMIT_TEMP_MIN_BG[1])
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, app.aaps.core.ui.R.string.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG[0], HardLimits.LIMIT_TEMP_MAX_BG[1])
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }
        val insulinDivisor = when {
            insulin.iCfg.peak > 65 -> 55 // rapid peak: 75
            insulin.iCfg.peak > 50 -> 65 // ultra rapid peak: 55
            else                   -> 45 // lyumjev peak: 45
        }

        var autosensResult = AutosensResult()
        val tddStatus: TddStatus?
        val variableSensitivity = 0.0
        val tdd = 0.0
        if (true) { // FIX: Always run, DetermineBasalAIMI2 handles dynIsfMode internally
            val tdd7P: Double = preferences.get(DoubleKey.OApsAIMITDD7)
//
// // Plancher pour éviter des TDD trop faibles au démarrage
            val minTDD = 10.0
//
// Récupération et ajustement du TDD sur 7 jours
            val tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))
            if (tdd7D != null && tdd7D.data.totalAmount > tdd7P && tdd7D.data.totalAmount > 1.3 * tdd7P) {
                tdd7D.data.totalAmount = 1.2 * tdd7P
                aapsLogger.info(LTag.APS, "TDD for 7 days limited to 10% increase. New TDD7D: ${tdd7D.data.totalAmount}")
            }
            if (tdd7D != null && tdd7D.data.totalAmount < tdd7P * 0.9) {
                tdd7D.data.totalAmount = tdd7P * 0.9
                aapsLogger.info(LTag.APS, "TDD for 7 days was too low. Adjusted to 90% of TDD7P: ${tdd7D.data.totalAmount}")
            }

            // Calcul du TDD sur 2 jours
            var tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.data?.totalAmount ?: 0.0
            if (tdd2Days == 0.0 || tdd2Days < tdd7P) tdd2Days = tdd7P
//
            val tdd2DaysPerHour = tdd2Days / 24
            val tddLast4H = tdd2DaysPerHour * 4
//
// Calcul du TDD sur 1 jour avec une limite minimale pour éviter des instabilités
            var tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount ?: 0.0
            if (tddDaily == 0.0 || tddDaily < tdd7P / 2) tddDaily = maxOf(tdd7P, minTDD)

            if (tddDaily > tdd7P && tddDaily > 1.1 * tdd7P) {
                tddDaily = 1.1 * tdd7P
                aapsLogger.info(LTag.APS, "TDD for 1 day limited to 10% increase. New TDDDaily: $tddDaily")
            }
// // Calcul du TDD sur 24 heures
            var tdd24Hrs = tddCalculator.calculateDaily(-24, 0)?.totalAmount ?: 0.0
            if (tdd24Hrs == 0.0) tdd24Hrs = tdd7P
            val tdd24HrsPerHour = tdd24Hrs / 24
            val tddLast8to4H = tdd24HrsPerHour * 4
// // Calcul pondéré du TDD récent pour éviter les fluctuations extrêmes
            val tddWeightedFromLast8H = ((1.2 * tdd2DaysPerHour) + (0.3 * tddLast4H) + (0.5 * tddLast8to4H)) * 3
            val tdd = (tddWeightedFromLast8H * 0.20) + (tdd2Days * 0.50) + (tddDaily * 0.30)

            // On récupère la glycémie et le delta actuel
            val gsData = glucoseStatusProvider.glucoseStatusData
            val currentBG = gsData?.glucose
            if (currentBG == null) {
                aapsLogger.error(LTag.APS, "Données de glycémie indisponibles, impossibilité de calculer l'ISF adaptatif.")
                return@withContext
            }
            val currentDelta = gsData?.delta
            val recentDeltas = getRecentDeltas()
            val predictedDelta = predictedDelta(recentDeltas)

            // Calcul adaptatif de l'ISF via la fonction centralisée encapsulant le tout (incluant l'alimentation du cache)
            val (source, calcSensitivity) = calculateVariableIsf(now)
            var variableSensitivity = calcSensitivity ?: profile.getProfileIsfMgdl()
            
            aapsLogger.debug(LTag.APS, "Adaptive ISF computed (source: $source): $variableSensitivity for BG: $currentBG, currentDelta: $currentDelta, predictedDelta: $predictedDelta")

            // 🏥 Apply Physio ISF Modulation to Dynamic ISF (it might already be in calculateVariableIsf, but applying it if not fully wrapped)
            // (calculateVariableIsf does apply physioMults internally before returning blended, 
            // but if we fell back to profile ISF, we apply it here for safety)
            if (source == "OFF" || calcSensitivity == null) {
                if (physioMults.isfFactor != 1.0) {
                    variableSensitivity *= physioMults.isfFactor
                    aapsLogger.debug(LTag.APS, "🏥 LOOP: DynISF modulated: $variableSensitivity (x${physioMults.isfFactor})")
                }
                // Imposition des bornes
                variableSensitivity = variableSensitivity.coerceIn(5.0, 300.0)
            }
            
            aapsLogger.debug(LTag.APS, "Final adaptive ISF after clamping: $variableSensitivity")

// 🔹 Création du résultat final (Convention: Ratio < 1 = Résistant)
            autosensResult = AutosensResult(
                ratio = tdd2Days / tdd24Hrs,
                ratioFromTdd = tdd2Days / tdd24Hrs,
                ratioFromCarbs = 1.0 
            )

            // 🧠 AIMI BRAIN INTEGRATION (UnifiedReactivityLearner)
            // "The Cognitive Bridge": Adjusts BOTH Sensitivity (ISF) and Resistance (Autosens Ratio)
            try {
                // 🚀 TRIPLE-SIGNAL CONFIRMATION for Confirmed Rise
                val gsAimi = gsData as? GlucoseStatusAIMI
                val accel = gsAimi?.bgAcceleration ?: 0.0
                val combDelta = ((gsData?.delta ?: 0.0) + predictedDelta) / 2.0
                val isConfirmedHighRise = (gsData?.glucose ?: 0.0) > 150.0 && combDelta > 1.5 && accel > 0.4

                unifiedReactivityLearner.processIfNeeded(isConfirmedHighRise)
                var brainFactor = unifiedReactivityLearner.getCombinedFactor()
                
                // 🚨 SAFETY OVERRIDE (FCL 10.3) - Refined for "Blind Spot" Removal:
                // If we are in Hyper (>150) AND Rising/Stable, we MUST NOT be protective (<1.0).
                // ANTI-LAG: Lower threshold to 110 if rising fast (delta > 3.0)
                val isFastRise = (gsData?.delta ?: 0.0) > 3.0
                val overrideThreshold = if (isFastRise) 110.0 else 150.0
                val isHyper = (gsData?.glucose ?: 0.0) > overrideThreshold
                val isRising = (gsData?.delta ?: 0.0) > -0.5
                
                if (isHyper && isRising && brainFactor < 1.0) {
                    aapsLogger.debug(LTag.APS, "🧠 Brain Override: IGNORING protective factor ${"%.2f".format(brainFactor)} because BG ${gsData?.glucose ?: 0.0} is > $overrideThreshold & stable/rising.")
                    brainFactor = 1.0
                }

                // 🚀 EXPLOSIVE RISE BOOST: If delta is very high, force a slight aggressive factor
                if (glucoseStatus.delta > 6.0 && brainFactor < 1.1) {
                    aapsLogger.debug(LTag.APS, "🚀 Brain Boost: Forcing factor 1.1 due to explosive rise (delta=${glucoseStatus.delta})")
                    brainFactor = 1.1
                }

                if (brainFactor != 1.0) {
                    val originalRatio = autosensResult.ratio
                    val originalISF = variableSensitivity
                    
                    // 1. Modulate Autosens Ratio (Basal/Targets/ISF)
                    // High Brain Factor (Aggressive) -> Ratio decreases (e.g. 0.8 / 1.5 = 0.53) -> More Resistant
                    autosensResult.ratio = originalRatio / brainFactor
                    
                    // 🚨 IMPORTANT: variableSensitivity (Dynamic ISF) is NO LONGER modulated here.
                    // It will be modulated by autosensResult.ratio in DetermineBasalAIMI2.kt 
                    // to ensure a single, consistent point of application for the "Resistance" multiplier.
                    
                    aapsLogger.debug(LTag.APS, "🧠 AIMI Brain Override: " +
                        "Autosens ${"%.2f".format(originalRatio)}->${"%.2f".format(autosensResult.ratio)} " +
                        "(Factor ${"%.2f".format(brainFactor)})")
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "Failed to apply AIMI Brain factor", e)
            }

            val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
            val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
            var currentActivity = 0.0
            for (i in -4..0) { //MP: -4 to 0 calculates all the insulin active during the last 5 minutes
                val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(i.toLong()), profile)
                currentActivity += iob.activity
            }
            var futureActivity = 0.0
            
            // 🌀 Cosine Gate: Apply Peak Shift
            val activityPredTimePK = insulin.iCfg.peak + physioMults.peakShiftMinutes
            // Ensure peak time remains reasonable (min 35m)
            val safepk = activityPredTimePK.coerceAtLeast(35)
            
            for (i in -4..0) { //MP: calculate 5-minute-insulin activity centering around peakTime
                val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(safepk.toLong() - i), profile)
                futureActivity += iob.activity
            }
            val sensorLag = -10L //MP Assume that the glucose value measurement reflect the BG value from 'sensorlag' minutes ago & calculate the insulin activity then
            var sensorLagActivity = 0.0
            for (i in -4..0) {
                val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(sensorLag - i), profile)
                sensorLagActivity += iob.activity
            }

            val activityHistoric = -20L //MP Activity at the time in minutes from now. Used to calculate activity in the past to use as target activity.
            var historicActivity = 0.0
            for (i in -2..2) {
                val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(activityHistoric - i), profile)
                historicActivity += iob.activity
            }
// Récupère GS standard + features AIMI
            val pack = glucoseStatusCalculatorAimi.compute(allowOldData = true)
            val gs = pack.gs ?: run {
                rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
                aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
                return@withContext
            }
            val f = pack.features

// Construit l’objet attendu par determine_basal
            val glucoseStatusAimi = GlucoseStatusAIMI(
                glucose         = gs.glucose,
                noise           = gs.noise,
                delta           = gs.delta,
                shortAvgDelta   = gs.shortAvgDelta,
                longAvgDelta    = gs.longAvgDelta,
                date            = gs.date,

                // Champs AIMI disponibles
                duraISFminutes  = f?.stable5pctMinutes ?: 0.0,
                deltaPl         = f?.delta5Prev ?: 0.0,
                deltaPn         = f?.delta5Next ?: 0.0,
                bgAcceleration  = f?.accel ?: 0.0,
                corrSqu         = f?.corrR2 ?: 0.0,

                // Champs non exposés par AimiBgFeatures => valeurs neutres
                duraISFaverage  = 0.0,
                parabolaMinutes = 0.0,
                a0              = 0.0,
                a1              = 0.0,
                a2              = 0.0
            )
            futureActivity = Round.roundTo(futureActivity, 0.0001)
            sensorLagActivity = Round.roundTo(sensorLagActivity, 0.0001)
            historicActivity = Round.roundTo(historicActivity, 0.0001)
            currentActivity = Round.roundTo(currentActivity, 0.0001)
            // === PK/PD: calcule un pkpdScale cohérent et le mémorise ===
            // === PK/PD: calcule un pkpdScale cohérent et le mémorise (SANS dyn ISF) ===
            val nowMs = dateUtil.now()

// valeurs BG/delta déjà dispo dans invoke (glucoseStatus)
            val bgNow = glucoseStatus.glucose
            val deltaNow = glucoseStatus.delta

// IOB instantané
            val iobNow = iobCobCalculator.calculateFromTreatmentsAndTemps(nowMs, profile).iob

// Utilise le TDD 24h que tu as déjà calculé/chargé dans invoke (évite les IO coûteuses)
            val tdd24ForPk = tdd24Hrs  // garde ta variable existante ici (Double)

// IMPORTANT : passer un ISF "profil brut" pour éviter toute ré-entrée dans dynISF
            val profileIsfRaw = profile.getProfileIsfMgdl()   // mg/dL/U du profil, SANS dynamique

            val pkpdRuntimeNow = pkpdIntegration.computeRuntime(
                epochMillis = nowMs,
                bg = bgNow,
                deltaMgDlPer5 = deltaNow,
                iobU = iobNow,
                carbsActiveG = 0.0,          // branche tes carbs actifs réels si tu les as ici
                windowMin = 240,             // fenêtre standard (4h) – ajuste si besoin
                exerciseFlag = false,        // remplace par ton flag 'sportTime' si dispo ICI
                profileIsf = profileIsfRaw,  // ← **PROFIL BRUT, PAS getIsfMgdl()**
                tdd24h = tdd24ForPk,
                combinedDelta = deltaNow,    // Fallback simple car combinedDelta non dispo ici
                uamConfidence = AimiUamHandler.confidenceOrZero()
            )

            lastPkpdScale = pkpdRuntimeNow?.pkpdScale ?: 1.0
            aapsLogger.debug(LTag.APS, "PK/PD: pkpdScale=$lastPkpdScale (bg=$bgNow, delta=$deltaNow, iob=$iobNow, tdd24=$tdd24ForPk, isfRaw=$profileIsfRaw)")
            val tdd4D = tddCalculator.averageTDD(tddCalculator.calculate(4, allowMissingDays = false))
            val oapsProfile = OapsProfileAimi(
                dia = eff.iCfg.dia,
                min_5m_carbimpact = 0.0, // not used
                max_iob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
                max_daily_basal = profile.getMaxDailyBasal(),
                max_basal = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
                min_bg = minBg,
                max_bg = maxBg,
                target_bg = targetBg,
                carb_ratio = profile.getIc(),
                sens = profile.getIsfMgdl("OpenAPSAIMIPlugin") * physioMults.isfFactor, // 🏥 ISF Modulation
                autosens_adjust_targets = false, // not used
                max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier) * physioMults.smbFactor, // 🏥 SMB Cap modulation
                current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier) * physioMults.basalFactor, // 🏥 Basal Cap modulation
                lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
                high_temptarget_raises_sensitivity = false,
                low_temptarget_lowers_sensitivity = false,
                sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
                resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
                adv_target_adjustments = SMBDefaults.adv_target_adjustments,
                exercise_mode = SMBDefaults.exercise_mode,
                half_basal_exercise_target = SMBDefaults.half_basal_exercise_target,
                maxCOB = SMBDefaults.maxCOB,
                skip_neutral_temps = pump.setNeutralTempAtFullHour(),
                remainingCarbsCap = SMBDefaults.remainingCarbsCap,
                enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
                A52_risk_enable = SMBDefaults.A52_risk_enable,
                SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
                enableSMB_with_COB = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
                enableSMB_with_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
                allowSMB_with_high_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
                enableSMB_always = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && advancedFiltering,
                enableSMB_after_carbs = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && advancedFiltering,
                maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
                maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
                bolus_increment = pump.pumpDescription.bolusStep,
                carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
                current_basal = ch.fromPump(activePlugin.activePump.baseBasalRate) * physioMults.basalFactor, // 🏥 Basal Rate Modulation
                temptargetSet = isTempTarget,
                autosens_max = preferences.get(DoubleKey.AutosensMax),
                out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
                variable_sens = variableSensitivity,
                insulinDivisor = insulinDivisor,
                TDD = if (tdd4D == null) preferences.get(DoubleKey.OApsAIMITDD7) else tdd,
                peakTime = activityPredTimePK.toDouble(),
                futureActivity = futureActivity,
                sensorLagActivity = sensorLagActivity,
                historicActivity = historicActivity,
                currentActivity = currentActivity
            )

            val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()
            val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

            aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal AIMI <<<")
            aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
            aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
            aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
            aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
            aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
            aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
            aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
            aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
            aapsLogger.debug(LTag.APS, "DynIsfMode:         $dynIsfMode")

            determineBasalaimiSMB2.determine_basal(
                glucose_status = glucoseStatusAimi,
                currenttemp = currentTemp,
                iob_data_array = iobArray,
                profile = oapsProfile,
                autosens_data = autosensResult,
                mealData = mealData,
                microBolusAllowed = microBolusAllowed,
                currentTime = now,
                flatBGsDetected = flatBGsDetected,
                dynIsfMode = dynIsfMode,
                uiInteraction = uiInteraction,
                extraDebug = physioMults.detailedReason
            ).also {
                val determineBasalResult = apsResultProvider.get().with(it)
                
                // 🔮 FCL 11.0: Force Copy Predictions via JSON (Manual Construction)
                // 🔮 FCL 11.0: Force Copy Predictions via JSON (Manual Construction)
                if (it.predBGs != null) {
                    val count = it.predBGs?.IOB?.size ?: 0
                    aapsLogger.debug(LTag.APS, "Plugin: Injecting predictions via JSON manually (Size: $count)")
                    try {
                        val predJson = JSONObject()
                        // Manual array copy to ensure data transfer
                        // Note: Using JSONArray constructor or equivalent
                        predJson.put("IOB", org.json.JSONArray(it.predBGs?.IOB))
                        predJson.put("COB", org.json.JSONArray(it.predBGs?.COB))
                        predJson.put("ZT",  org.json.JSONArray(it.predBGs?.ZT))
                        predJson.put("UAM", org.json.JSONArray(it.predBGs?.UAM))
                        
                        // Inject into the main result JSON
                        determineBasalResult.json()?.put("predBGs", predJson)
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.APS, "Failed to inject JSON predictions: $e")
                    }

                    // 🔮 FCL 11.1: Force Populate predictionsAsGv for UI (OverviewViewModel)
                    // If 'with(RT)' failed to populate the list, we do it manually here.
                    if (determineBasalResult.predictionsAsGv.isEmpty()) {
                        it.predBGs?.IOB?.forEachIndexed { index, value ->
                             val time = now + index * 300000L // 5 mins
                             val gv = GV(
                                 timestamp = time,
                                 value = value.toDouble(),
                                 raw = value.toDouble(),
                                 trendArrow = TrendArrow.NONE,
                                 noise = 0.0,
                                 sourceSensor = SourceSensor.IOB_PREDICTION
                             )
                             determineBasalResult.predictionsAsGv.add(gv)
                         }
                    }
                }

                // Preserve input data
                determineBasalResult.inputConstraints = inputConstraints
                determineBasalResult.autosensResult = autosensResult
                determineBasalResult.iobData = iobArray
                determineBasalResult.glucoseStatus = glucoseStatus
                determineBasalResult.currentTemp = currentTemp
                determineBasalResult.oapsProfileAimi = oapsProfile
                determineBasalResult.mealData = mealData
                lastAPSResult = determineBasalResult
                lastAPSRun = now
                aapsLogger.debug(LTag.APS, "Result: $it")
                rxBus.send(EventAPSCalculationFinished())
            }

            rxBus.send(EventOpenAPSUpdateGui())
        }
    }

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }
    fun detectMealOnset(delta: Float, predictedDelta: Float, acceleration: Float): Boolean {
        val combinedDelta = (delta + predictedDelta) / 2.0f
        return combinedDelta > 3.0f && acceleration > 1.2f
    }

    override fun applyBasalConstraints(
        absoluteRate: Constraint<Double>,
        profile: Profile
    ): Constraint<Double> {
        // ────────────────────────────────────────────────────
        // 1️⃣ On détecte si l’on est en mode “meal” ou “early autodrive”
        val therapy = Therapy(persistenceLayer).also { it.updateStatesBasedOnTherapyEvents() }
        
        // 🎯 Context Integration (Remote/AI)
        val contextSnapshot = contextManager.getSnapshot(dateUtil.now())
        
        val isMealMode = therapy.snackTime
            || therapy.highCarbTime
            || therapy.mealTime
            || therapy.lunchTime
            || therapy.dinnerTime
            || therapy.bfastTime
            || contextSnapshot.hasMealRisk // 🍕 Remote "Lunch/Meal" triggers this

        val isSportMode = therapy.sportTime || contextSnapshot.hasActivity // 🏃 Remote "Sport" triggers this

        val hour = Calendar.getInstance()[Calendar.HOUR_OF_DAY]
        val night = hour <= 7
        val isAutodriveEnabled = preferences.get(BooleanKey.OApsAIMIautoDrive) || preferences.get(BooleanKey.OApsAIMIautoDriveActive)
        val smb = glucoseStatusCalculatorAimi.getGlucoseStatusData(false) ?: return absoluteRate

        val isEarlyAutodrive = !night && !isMealMode && !isSportMode && isAutodriveEnabled &&
            determineBasalaimiSMB2.isAutodriveEngaged()

        val isSpecialMode = isMealMode || isEarlyAutodrive || preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)

        // ────────────────────────────────────────────────────
        // 2️⃣ On choisit la bonne pref en fonction du mode
        var maxBasal = when {
            isMealMode       -> preferences.get(DoubleKey.meal_modes_MaxBasal)
            isEarlyAutodrive -> preferences.get(DoubleKey.autodriveMaxBasal)
            else             -> preferences.get(DoubleKey.ApsMaxBasal)
        }

        if (isEnabled()) {
            // 3️⃣ On remonte au maxDailyBasal si besoin
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(
                    rh.gs(R.string.increasing_max_basal),
                    this
                )
            }

            // 4️⃣ On bride toujours sur maxBasal
            absoluteRate.setIfSmaller(
                maxBasal,
                rh.gs(
                    app.aaps.core.ui.R.string.limitingbasalratio,
                    maxBasal,
                    rh.gs(R.string.maxvalueinpreferences)
                ),
                this
            )

            // ───> **Si on est dans un mode spécial, on s’arrête là :**
            if (isSpecialMode) {
                return absoluteRate
            }

            // ────────────────────────────────────────────────────
            // 5️⃣ Sinon, on applique en plus le multiplicateur “current basal”
            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(
                    app.aaps.core.ui.R.string.limitingbasalratio,
                    maxFromBasalMultiplier,
                    rh.gs(R.string.max_basal_multiplier)
                ),
                this
            )

            // 6️⃣ Et le multiplicateur “daily basal”
            val maxDailyMultiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxDailyMultiplier * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromDaily,
                rh.gs(
                    app.aaps.core.ui.R.string.limitingbasalratio,
                    maxFromDaily,
                    rh.gs(R.string.max_daily_basal_multiplier)
                ),
                this
            )
        }

        return absoluteRate
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            // DynISF mode
            if (!preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity))
                value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        } else {
            // SMB mode
            val enabled = preferences.get(BooleanKey.ApsUseAutosens)
            if (!enabled) value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        }
        return value
    }

    override fun configuration(): JsonObject =
        JsonObject(emptyMap())
            .put(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .put(IntKey.ApsDynIsfAdjustmentFactor, preferences)

    override fun applyConfiguration(configuration: JsonObject) {
        configuration
            .store(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .store(IntKey.ApsDynIsfAdjustmentFactor, preferences)
    }

    /**
     * Required for [app.aaps.ui.compose.preferences.AllPreferencesScreen]: only plugins that return
     * a [PreferenceSubScreenDef] appear in the Compose settings list. XML-only [addPreferenceScreen]
     * does not surface there.
     */
    override fun getPreferenceScreenContent(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "openapsaimi_settings",
            titleResId = R.string.openapsaimi,
            icon = pluginDescription.icon,
            items = buildAimiComposePreferenceItems(),
        )

    /**
     * OpenAPS/SMB core keys (same order as [app.aaps.plugins.aps.openAPSSMB.OpenAPSSMBPlugin]) +
     * nested AIMI sections so Compose settings match most of the legacy tree.
     */
    private fun buildAimiComposePreferenceItems(): List<PreferenceItem> = buildList {
        add(DoubleKey.ApsMaxBasal)
        add(DoubleKey.ApsSmbMaxIob)
        add(BooleanKey.ApsUseDynamicSensitivity)
        add(BooleanKey.ApsUseAutosens)
        add(IntKey.ApsDynIsfAdjustmentFactor)
        add(UnitDoubleKey.ApsLgsThreshold)
        add(BooleanKey.ApsDynIsfAdjustSensitivity)
        add(BooleanKey.ApsSensitivityRaisesTarget)
        add(BooleanKey.ApsResistanceLowersTarget)
        add(BooleanKey.ApsUseSmb)
        add(BooleanKey.ApsUseSmbWithHighTt)
        add(BooleanKey.ApsUseSmbAlways)
        add(BooleanKey.ApsUseSmbWithCob)
        add(BooleanKey.ApsUseSmbWithLowTt)
        add(BooleanKey.ApsUseSmbAfterCarbs)
        add(IntKey.ApsMaxSmbFrequency)
        add(IntKey.ApsMaxMinutesOfBasalToLimitSmb)
        add(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb)
        add(BooleanKey.ApsUseUam)
        add(IntKey.ApsCarbsRequestThreshold)
        add(
            PreferenceSubScreenDef(
                key = "openapsaimi_absorption_advanced",
                titleResId = app.aaps.core.ui.R.string.advanced_settings_title,
                items = listOf(
                    ApsIntentKey.LinkToDocs,
                    BooleanKey.ApsAlwaysUseShortDeltas,
                    DoubleKey.ApsMaxDailyMultiplier,
                    DoubleKey.ApsMaxCurrentBasalMultiplier,
                ),
            )
        )
        add(
            PreferenceSubScreenDef(
                key = "aimi_user_preferences",
                titleResId = R.string.user_preferences,
                items = aimiComposeUserPreferenceItems(),
            )
        )
        add(aimiComposeManualModesSubScreen())
        add(aimiComposeAutodriveSubScreen())
    }

    private fun aimiComposeStringArrayMap(@ArrayRes valuesId: Int, @ArrayRes labelsId: Int): Map<String, String> {
        val values = rh.gsa(valuesId)
        val labels = rh.gsa(labelsId)
        require(values.size == labels.size) { "Array size mismatch: valuesId=$valuesId labelsId=$labelsId" }
        return values.indices.associate { values[it] to labels[it] }
    }

    private fun aimiComposeUserPreferenceItems(): List<PreferenceItem> = buildList {
        add(
            PreferenceSubScreenDef(
                key = "aimi_compose_ai_keys",
                titleResId = R.string.aimi_prefs_ai_title,
                items = listOf(
                    StringKey.AimiAdvisorProvider.withEntries(
                        mapOf(
                            "OPENAI" to rh.gs(R.string.aimi_prefs_provider_openai),
                            "GEMINI" to rh.gs(R.string.aimi_prefs_provider_gemini),
                            "DEEPSEEK" to rh.gs(R.string.aimi_prefs_provider_deepseek),
                            "CLAUDE" to rh.gs(R.string.aimi_prefs_provider_claude),
                        )
                    ),
                    StringKey.AimiAdvisorOpenAIKey,
                    StringKey.AimiAdvisorGeminiKey,
                    StringKey.AimiAdvisorDeepSeekKey,
                    StringKey.AimiAdvisorClaudeKey,
                    BooleanKey.OApsAIMIAdvisorPersonalOrefMl,
                    BooleanKey.OApsAIMIAdvisorLlmRichOref,
                ),
            )
        )
        add(
            PreferenceSubScreenDef(
                key = "aimi_compose_sos",
                titleResId = R.string.aimi_sos_title,
                items = buildList {
                    add(BooleanKey.AimiEmergencySosEnable)
                    add(StringKey.AimiEmergencySosPhone)
                    add(IntKey.AimiEmergencySosThreshold)
                    add(ApsIntentKey.AimiSosPermissions)
                },
            )
        )
        add(
            PreferenceSubScreenDef(
                key = "aimi_compose_physio",
                titleResId = R.string.aimi_physio_title,
                items = buildList {
                    add(BooleanKey.AimiPhysioAssistantEnable)
                    add(ApsIntentKey.AimiHealthConnectPermissions)
                    add(AimiStringKey.ActivitySourceMode)
                    add(BooleanKey.AimiPhysioSleepDataEnable)
                    add(BooleanKey.AimiPhysioHRVDataEnable)
                    add(BooleanKey.AimiPhysioLLMAnalysisEnable)
                    add(BooleanKey.AimiPhysioDebugLogs)
                },
            )
        )
        add(BooleanKey.OApsAIMIMLtraining)
        add(DoubleKey.OApsAIMIMaxSMB)
        add(DoubleKey.OApsAIMIHighBGMaxSMB)
        add(DoubleKey.OApsAIMIweight)
        add(DoubleKey.OApsAIMICHO)
        add(DoubleKey.OApsAIMITDD7)
        add(aimiComposePkpdSubScreen())
        add(aimiComposeAdaptiveBasalSubScreen())
        add(aimiComposeT3cSubScreen())
        add(aimiComposeTrajectorySubScreen())
        add(BooleanKey.OApsxdriponeminute)
        add(aimiComposeWomenCycleSubScreen())
        add(aimiComposeInflammatorySubScreen())
        add(aimiComposeThyroidModuleSubScreen())
        add(BooleanKey.OApsAIMIpregnancy)
        add(AimiStringKey.PregnancyDueDateString)
        add(BooleanKey.OApsAIMIhoneymoon)
        add(BooleanKey.OApsAIMInight)
        add(BooleanKey.OApsAIMIUnifiedReactivityEnabled)
        add(aimiComposeEndometriosisSubScreen())
        add(aimiComposeAiAuditorSubScreen())
        add(aimiComposeNightGrowthSubScreen())
    }

    private fun aimiComposeAdaptiveBasalSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_adaptive_basal",
            titleResId = R.string.oaps_aimi_adaptive_basal_title,
            items = buildList {
                add(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)
                add(DoubleKey.OApsAIMIAdaptiveBasalMaxScaling)
                add(DoubleKey.OApsAIMIGovernanceHypoRateEnter)
                add(DoubleKey.OApsAIMIGovernanceHypoRateExit)
                add(DoubleKey.OApsAIMIGovernanceHypoBgMgdl)
                add(DoubleKey.OApsAIMIGovernanceSevereHypoBgMgdl)
                add(DoubleKey.OApsAIMIGovernanceHoldBasalFloorRate)
                add(DoubleKey.OApsAIMIGovernanceHoldBasalDecayRate)
                add(DoubleKey.OApsAIMIGovernanceHoldAggFloorRate)
                add(DoubleKey.OApsAIMIGovernanceHoldAggDecayRate)
                add(DoubleKey.OApsAIMIGovernanceHoldBasalFloorSevere)
                add(DoubleKey.OApsAIMIGovernanceHoldBasalDecaySevere)
                add(DoubleKey.OApsAIMIGovernanceHoldAggFloorSevere)
                add(DoubleKey.OApsAIMIGovernanceHoldAggDecaySevere)
                add(DoubleKey.OApsAIMIGovernanceAnticipationLookbackSamples)
                add(DoubleKey.OApsAIMIGovernanceAnticipationMarginMgdl)
                add(DoubleKey.OApsAIMIGovernanceAnticipationHypoDamp)
                add(DoubleKey.OApsAIMIGovernanceAnticipationDecayBlendMax)
            },
        )

    private fun aimiComposeT3cSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_t3c",
            titleResId = R.string.aimi_t3c_settings_title,
            items = listOf(
                BooleanKey.OApsAIMIT3cBrittleMode,
                DoubleKey.OApsAIMIT3cActivationThreshold,
                DoubleKey.OApsAIMIT3cAggressiveness,
                DoubleKey.OApsAIMIT3cAnticipationStrength,
            ),
        )

    private fun aimiComposeTrajectorySubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_trajectory",
            titleResId = R.string.aimi_trajectory_section_title,
            items = listOf(BooleanKey.OApsAIMITrajectoryGuardEnabled),
        )

    private fun aimiComposeWomenCycleSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_wcycle",
            titleResId = R.string.wcycle_preferences,
            items = buildList {
                add(BooleanKey.OApsAIMIwcycle)
                add(StringKey.OApsAIMIWCycleTrackingMode.withEntries(aimiComposeStringArrayMap(R.array.wcycle_tracking_values, R.array.wcycle_tracking_entries)))
                add(StringKey.OApsAIMIWCycleContraceptive.withEntries(aimiComposeStringArrayMap(R.array.wcycle_contraceptive_values, R.array.wcycle_contraceptive_entries)))
                add(DoubleKey.OApsAIMIwcycledateday)
                add(IntKey.OApsAIMIWCycleAvgLength)
                add(BooleanKey.OApsAIMIWCycleShadow)
                add(BooleanKey.OApsAIMIWCycleRequireConfirm)
                add(DoubleKey.OApsAIMIWCycleClampMin)
                add(DoubleKey.OApsAIMIWCycleClampMax)
            },
        )

    private fun aimiComposeInflammatorySubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_inflammatory",
            titleResId = R.string.aimi_inflammatory_diseases_title,
            items = listOf(
                StringKey.OApsAIMIWCycleThyroid.withEntries(aimiComposeStringArrayMap(R.array.wcycle_thyroid_values, R.array.wcycle_thyroid_entries)),
                StringKey.OApsAIMIWCycleVerneuil.withEntries(aimiComposeStringArrayMap(R.array.wcycle_verneuil_values, R.array.wcycle_verneuil_entries)),
            ),
        )

    private fun aimiComposeThyroidModuleSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_thyroid_module",
            titleResId = R.string.oaps_aimi_thyroid_title,
            items = buildList {
                add(BooleanKey.OApsAIMIThyroidEnabled)
                add(StringKey.OApsAIMIThyroidMode.withEntries(aimiComposeStringArrayMap(R.array.oaps_aimi_thyroid_mode_values, R.array.oaps_aimi_thyroid_mode_entries)))
                add(StringKey.OApsAIMIThyroidManualStatus.withEntries(aimiComposeStringArrayMap(R.array.oaps_aimi_thyroid_status_values, R.array.oaps_aimi_thyroid_status_entries)))
                add(StringKey.OApsAIMIThyroidTreatmentPhase.withEntries(aimiComposeStringArrayMap(R.array.oaps_aimi_thyroid_phase_values, R.array.oaps_aimi_thyroid_phase_entries)))
                add(StringKey.OApsAIMIThyroidGuardLevel.withEntries(aimiComposeStringArrayMap(R.array.oaps_aimi_thyroid_guard_values, R.array.oaps_aimi_thyroid_guard_entries)))
                add(BooleanKey.OApsAIMIThyroidLogVerbosity)
            },
        )

    private fun aimiComposeEndometriosisSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_endo",
            titleResId = R.string.endo_preferences_title,
            items = listOf(
                BooleanKey.AimiEndometriosisEnable,
                BooleanKey.AimiEndometriosisPainFlare,
                IntKey.AimiEndometriosisFlareDuration,
                DoubleKey.AimiEndometriosisBasalMult,
                DoubleKey.AimiEndometriosisSmbDampen,
            ),
        )

    private fun aimiComposeAiAuditorSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_ai_auditor",
            titleResId = R.string.aimi_ai_auditor_section_title,
            items = buildList {
                add(BooleanKey.AimiAuditorEnabled)
                add(
                    StringKey.AimiAuditorMode.withEntries(
                        mapOf(
                            "AUDIT_ONLY" to "Audit only (log verdicts)",
                            "SOFT_MODULATION" to "Soft modulation (apply if confident)",
                            "HIGH_RISK_ONLY" to "High risk only (apply with risk flags)",
                        )
                    )
                )
                add(IntKey.AimiAuditorMaxPerHour)
                add(IntKey.AimiAuditorTimeoutSeconds)
                add(IntKey.AimiAuditorMinConfidence)
            },
        )

    private fun aimiComposeNightGrowthSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_ngr",
            titleResId = R.string.oaps_aimi_ngr_title,
            items = listOf(
                BooleanKey.OApsAIMINightGrowthEnabled,
                IntKey.OApsAIMINightGrowthAgeYears,
                StringKey.OApsAIMINightGrowthStart,
                StringKey.OApsAIMINightGrowthEnd,
                DoubleKey.OApsAIMINightGrowthMaxIobExtra,
            ),
        )

    private fun aimiComposeManualModesSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_training_ml_modes",
            titleResId = R.string.training_ml_modes_preferences,
            items = buildList {
                add(DoubleKey.meal_modes_MaxBasal)
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_breakfast",
                        titleResId = R.string.training_ml_breakfast_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMIBFPrebolus,
                            DoubleKey.OApsAIMIBFPrebolus2,
                            DoubleKey.OApsAIMIBFFactor,
                            IntKey.OApsAIMIBFinterval,
                        ),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_lunch",
                        titleResId = R.string.training_ml_lunch_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMILunchPrebolus,
                            DoubleKey.OApsAIMILunchPrebolus2,
                            DoubleKey.OApsAIMILunchFactor,
                            IntKey.OApsAIMILunchinterval,
                        ),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_dinner",
                        titleResId = R.string.training_ml_dinner_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMIDinnerPrebolus,
                            DoubleKey.OApsAIMIDinnerPrebolus2,
                            DoubleKey.OApsAIMIDinnerFactor,
                            IntKey.OApsAIMIDinnerinterval,
                        ),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_highcarb",
                        titleResId = R.string.training_ml_high_carb_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMIHighCarbPrebolus,
                            DoubleKey.OApsAIMIHighCarbPrebolus2,
                            DoubleKey.OApsAIMIHCFactor,
                            IntKey.OApsAIMIHCinterval,
                        ),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_snack",
                        titleResId = R.string.training_ml_snack_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMISnackPrebolus,
                            DoubleKey.OApsAIMISnackFactor,
                            IntKey.OApsAIMISnackinterval,
                        ),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_meal",
                        titleResId = R.string.training_ml_generic_meal_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMIMealPrebolus,
                            DoubleKey.OApsAIMIMealFactor,
                            IntKey.OApsAIMImealinterval,
                        ),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_sleep",
                        titleResId = R.string.training_ml_sleep_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMIsleepFactor,
                            IntKey.OApsAIMISleepinterval,
                        ),
                    )
                )
            },
        )

    private fun aimiComposeAutodriveSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_autodrive",
            titleResId = R.string.autodrive_preferences,
            items = buildList {
                add(BooleanKey.OApsAIMIautoDrive)
                add(BooleanKey.OApsAIMIautoDriveActive)
                add(DoubleKey.autodriveMaxBasal)
                add(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep)
                add(DoubleKey.OApsAIMIautodrivesmallPrebolus)
                add(DoubleKey.OApsAIMIautodrivePrebolus)
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_autodrive_prebolus_vars",
                        titleResId = R.string.autodrive_prebolus_variables,
                        items = listOf(
                            IntKey.OApsAIMIAutodriveBG,
                            DoubleKey.OApsAIMIcombinedDelta,
                            DoubleKey.OApsAIMIAutodriveDeviation,
                        ),
                    )
                )
            },
        )

    private fun aimiComposePkpdSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_pkpd",
            titleResId = R.string.oaps_aimi_pkpd_section_title,
            items = buildList {
                add(
                    ApsIntentKey.PkpdSetup.withCompose(
                        ComposeScreenContent { onBack ->
                            AimiPkpdSettingsScreen(
                                preferences = preferences,
                                onBack = onBack,
                            )
                        }
                    )
                )
                add(BooleanKey.OApsAIMIPkpdEnabled)
                add(DoubleKey.OApsAIMIPkpdInitialDiaH)
                add(DoubleKey.OApsAIMIPkpdInitialPeakMin)
                add(DoubleKey.OApsAIMIPkpdBoundsDiaMinH)
                add(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH)
                add(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin)
                add(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax)
                add(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH)
                add(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin)
                add(DoubleKey.OApsAIMIIsfFusionMinFactor)
                add(DoubleKey.OApsAIMIIsfFusionMaxFactor)
                add(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick)
                add(DoubleKey.OApsAIMISmbTailThreshold)
                add(DoubleKey.OApsAIMISmbTailDamping)
                add(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled)
                add(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor)
                add(DoubleKey.OApsAIMIRedCarpetRestoreThreshold)
                add(BooleanKey.OApsAIMIIobSurveillanceGuard)
                add(DoubleKey.OApsAIMIPriorityMaxIobFactor)
                add(DoubleKey.OApsAIMIPriorityMaxIobExtraU)
                add(DoubleKey.OApsAIMISmbExerciseDamping)
                add(DoubleKey.OApsAIMISmbLateFatDamping)
            },
        )

}
