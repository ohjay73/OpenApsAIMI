package app.aaps.plugins.main.general.dashboard.glass

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.openAPSAIMI.GlucoseStatusCalculatorAimi
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlTrainingCoordinator
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbModelStore
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStatePresentationBuilder
import app.aaps.plugins.aps.openAPSAIMI.pkpd.TrajectoryRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.main.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class GlassLoopDashboardViewModel @Inject constructor(
    private val activePlugin: ActivePlugin,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val tddCalculator: TddCalculator,
    private val tirCalculator: TirCalculator,
    private val glucoseStatusCalculatorAimi: GlucoseStatusCalculatorAimi,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences,
    private val resourceHelper: ResourceHelper,
    private val dateUtil: DateUtil,
    private val config: Config,
    private val basalNeuralLearner: BasalNeuralLearner,
    private val aimiStorageHelper: AimiStorageHelper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GlassLoopDashboardState())
    val uiState: StateFlow<GlassLoopDashboardState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val newState = withContext(Dispatchers.IO) {
                val result = activePlugin.activeAPS?.lastAPSResult
                val hasResult = result != null

                val requestedSmbText = if (hasResult)
                    resourceHelper.gs(CoreUiR.string.format_insulin_units, result?.smb ?: 0.0)
                else
                    "--"

                val targetBgText = if (hasResult)
                    profileUtil.fromMgdlToStringInUnits(result?.targetBG ?: 0.0)
                else
                    "--"

                val glucoseStatus = glucoseStatusProvider.glucoseStatusData
                val hasGlucose = glucoseStatus != null
                val delta5mMgdl = glucoseStatus?.delta ?: 0.0
                val shortAvgDeltaMgdl = glucoseStatus?.shortAvgDelta ?: 0.0
                val longAvgDeltaMgdl = glucoseStatus?.longAvgDelta ?: 0.0

                val iob = result?.iob
                val iobText = if (iob != null)
                    resourceHelper.gs(CoreUiR.string.format_insulin_units, iob.iob + iob.basaliob)
                else
                    "--"

                val oapsProfile = result?.oapsProfileAimi
                val maxIobProfileText = if (oapsProfile != null)
                    resourceHelper.gs(CoreUiR.string.format_insulin_units, oapsProfile.max_iob)
                else
                    "--"

                val maxSmbProfile = preferences.get(DoubleKey.OApsAIMIMaxSMB)
                val maxSmbProfileText = resourceHelper.gs(CoreUiR.string.format_insulin_units, maxSmbProfile)

                val variableSens = result?.variableSens
                val isfText = if (variableSens != null) {
                    val isfDisplay = profileUtil.fromMgdlToUnits(variableSens)
                    val isfUnitRes = if (profileFunction.getUnits() == GlucoseUnit.MGDL)
                        CoreUiR.string.profile_isf_units_mgdl
                    else
                        CoreUiR.string.profile_isf_units_mmol
                    resourceHelper.gs(R.string.dashboard_glass_loop_isf_value, isfDisplay, resourceHelper.gs(isfUnitRes))
                } else {
                    "--"
                }

                val stableMinutes = glucoseStatusCalculatorAimi.getAimiFeatures(allowOldData = true)?.stable5pctMinutes ?: 0.0

                // TddCalculator.calculateDaily's params are HOUR offsets, not days (see its KDoc) — a real 7-day
                // window is -168..0 hours, not -7..0 (which would be the last 7 hours). Divide by the same
                // 168-hour window to get the hourly rate averaged across the whole week.
                val tdd7 = tddCalculator.calculateDaily(-7L * 24L, 0L)
                val tdd7PerHour = (tdd7?.totalAmount ?: 0.0) / (7.0 * 24.0)

                val tirLow1h = tirCalculator.averageTIR(tirCalculator.calculateHour(70.0, 180.0)).belowPct() ?: 0.0
                val tirLow24h = tirCalculator.averageTIR(tirCalculator.calculateDaily(70.0, 180.0)).belowPct() ?: 0.0

                val nowEpochMs = dateUtil.now()
                val steps5m = persistenceLayer.getLastStepsCountFromTimeToTime(
                    nowEpochMs - 24 * 3_600_000L,
                    nowEpochMs
                )?.steps5min ?: 0

                val calendar = Calendar.getInstance()
                val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

                val lastRunTime = (result?.date ?: 0L).let { date -> if (date > 0L) dateUtil.timeString(date) else "" }

                val trajectoryCurves = TrajectoryRuntimeRepository.getLatest()
                val hasTrajectory = trajectoryCurves != null
                val trajectoryHybridText = trajectoryCurves?.hybridTerminal?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "--"
                val trajectoryIobText = trajectoryCurves?.iob?.lastOrNull()?.takeIf { it.isFinite() }?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "--"
                val trajectoryCobText = trajectoryCurves?.cobTerminal?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "--"

                val physioSnapshot = PatientStateRuntimeRepository.getLatest()
                val physioPresentation = physioSnapshot?.let {
                    PatientStatePresentationBuilder.build(it, dateUtil.now())
                }
                val hasPhysio = physioPresentation != null
                val physioModeText = physioPresentation?.modeHeadline ?: "--"
                val physioIntentText = physioPresentation?.intentSummary ?: "--"
                val physioThermalText = physioPresentation?.thermalSummary ?: "--"
                val physioUpdatedText = physioPresentation?.updatedSummary ?: ""

                val trainingCoordinator = BasalMlTrainingCoordinator.instance
                val hasMlTraining = trainingCoordinator != null
                val lastTrainedMs = trainingCoordinator?.lastTrainedAtMs() ?: 0L
                val mlLastTrainedText = if (lastTrainedMs > 0L)
                    resourceHelper.gs(R.string.dashboard_glass_loop_ml_last_trained_value, dateUtil.minAgoShort(lastTrainedMs))
                else
                    resourceHelper.gs(R.string.dashboard_glass_loop_ml_never_trained)
                val mlSampleCountText = basalNeuralLearner.getGovernanceSnapshot().sampleCount.toString()
                val mlCircuitOpen = trainingCoordinator?.isCircuitOpenNow() ?: false

                val basalModelFile = trainingCoordinator?.basalWeightsFile()
                val basalModelFileText = basalModelFile?.takeIf { it.exists() }?.let { dateUtil.dateAndTimeString(it.lastModified()) }
                    ?: resourceHelper.gs(R.string.dashboard_glass_loop_ml_file_missing)

                val smbModelFile = AimiSmbModelStore.modelFile(aimiStorageHelper.getAimiDirectory())
                val smbModelFileText = smbModelFile.takeIf { it.exists() }?.let { dateUtil.dateAndTimeString(it.lastModified()) }
                    ?: resourceHelper.gs(R.string.dashboard_glass_loop_ml_file_missing)

                GlassLoopDashboardState(
                    isLoading = false,
                    lastRunTime = lastRunTime,
                    requestedSmbText = requestedSmbText,
                    glucoseText = if (hasGlucose) profileUtil.fromMgdlToStringInUnits(glucoseStatus?.glucose ?: 0.0) else "--",
                    delta5mText = if (hasGlucose) profileUtil.fromMgdlToSignedStringInUnits(delta5mMgdl) else "--",
                    delta5mIsPositive = delta5mMgdl >= 0.0,
                    shortAvgDeltaText = if (hasGlucose) profileUtil.fromMgdlToSignedStringInUnits(shortAvgDeltaMgdl) else "--",
                    shortAvgDeltaIsPositive = shortAvgDeltaMgdl >= 0.0,
                    longAvgDeltaText = if (hasGlucose) profileUtil.fromMgdlToSignedStringInUnits(longAvgDeltaMgdl) else "--",
                    longAvgDeltaIsPositive = longAvgDeltaMgdl >= 0.0,
                    iobText = iobText,
                    targetBgText = targetBgText,
                    maxIobProfileText = maxIobProfileText,
                    maxSmbProfileText = maxSmbProfileText,
                    isfText = isfText,
                    stableMinutesText = resourceHelper.gs(CoreUiR.string.format_mins, stableMinutes.roundToInt()),
                    tdd7DaysPerHourText = resourceHelper.gs(CoreUiR.string.pump_base_basal_rate, tdd7PerHour),
                    tirLow1hText = resourceHelper.gs(CoreUiR.string.format_percent, tirLow1h.roundToInt()),
                    tirLow24hText = resourceHelper.gs(CoreUiR.string.format_percent, tirLow24h.roundToInt()),
                    steps5mText = resourceHelper.gs(R.string.dashboard_glass_loop_steps_5m, steps5m),
                    hourOfDay = hourOfDay,
                    isWeekend = isWeekend,
                    buildVersionText = config.VERSION_NAME,
                    hasTrajectory = hasTrajectory,
                    trajectoryHybridText = trajectoryHybridText,
                    trajectoryIobText = trajectoryIobText,
                    trajectoryCobText = trajectoryCobText,
                    hasPhysio = hasPhysio,
                    physioModeText = physioModeText,
                    physioIntentText = physioIntentText,
                    physioThermalText = physioThermalText,
                    physioUpdatedText = physioUpdatedText,
                    hasMlTraining = hasMlTraining,
                    mlLastTrainedText = mlLastTrainedText,
                    mlSampleCountText = mlSampleCountText,
                    mlCircuitOpen = mlCircuitOpen,
                    basalModelFileText = basalModelFileText,
                    smbModelFileText = smbModelFileText,
                )
            }

            _uiState.update { newState }
        }
    }
}
