package app.aaps.plugins.main.general.dashboard.glass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.keys.StringKey
import app.aaps.core.ui.UiMode
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardEmbeddedComposeState
import app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction
import app.aaps.plugins.main.general.dashboard.DashboardV2ToolsScreen
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState
import app.aaps.ui.compose.overview.graphs.ChartConfig
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.statusLights.StatusItem
import app.aaps.ui.compose.overview.statusLights.StatusUiState
import app.aaps.ui.compose.overview.statusLights.StatusViewModel

@Composable
internal fun GlassOverviewComposeEmbedded(
    overviewViewModel: OverviewViewModel,
    statusViewModel: StatusViewModel,
    graphViewModel: GraphViewModel,
    embeddedState: DashboardEmbeddedComposeState,
    availablePluginClassNames: Set<String>,
    onToolAction: (DashboardV2ToolAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by overviewViewModel.statusCardState.observeAsState()
    val statusLights by statusViewModel.uiState.collectAsStateWithLifecycle()
    val commands = LocalGlassHeroCommands.current
    var rangeHours by remember { mutableStateOf(6) }
    var showTools by remember { mutableStateOf(false) }

    AapsTheme {
        val preferences = LocalPreferences.current
        val darkModeValue by preferences.observe(StringKey.GeneralDarkMode).collectAsState()
        val isDark = when (UiMode.fromString(darkModeValue)) {
            UiMode.LIGHT  -> false
            UiMode.DARK   -> true
            UiMode.SYSTEM -> isSystemInDarkTheme()
        }
        val state = buildGlassUiState(status, statusLights)

        val bgReadings by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()
        val iobData by graphViewModel.iobGraphFlow.collectAsStateWithLifecycle()
        val treatmentData by graphViewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
        val chartConfig by graphViewModel.chartConfigFlow.collectAsStateWithLifecycle()
        val predictions by graphViewModel.predictionsFlow.collectAsStateWithLifecycle()
        val nowEpochMs = System.currentTimeMillis()
        val chartState = remember(rangeHours, bgReadings, iobData, treatmentData, chartConfig, predictions, status?.pumpStatusText) {
            buildGlassChartState(
                rangeHours = rangeHours,
                nowEpochMs = nowEpochMs,
                bgReadings = bgReadings,
                iobPoints = iobData.iob,
                boluses = treatmentData.boluses,
                carbs = treatmentData.carbs,
                chartConfig = chartConfig,
                mgdlToChartY = graphViewModel::glucoseMgdlToChartY,
                pumpStatusText = status?.pumpStatusText.orEmpty(),
                predictions = predictions,
            )
        }

        Box(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusAgoraCard(
                    state = state,
                    isDark = isDark,
                    onOpenLoop = commands::openLoop,
                    onOpenLoopDashboard = commands::openLoopDashboard,
                    onOpenTarget = commands::openTarget,
                    onOpenInsulin = commands::openInsulin,
                    onOpenPump = commands::openPump,
                    onOpenCannula = commands::openCannula,
                    onOpenBattery = commands::openBattery,
                    onOpenBasal = commands::openBasal,
                    onOpenSensorInsert = commands::openSensorInsert,
                    onOpenSensorQuality = commands::openSensorQuality,
                    onOpenTools = { showTools = true },
                )
                BgChartCard(
                    readings = chartState.bgReadings,
                    treatments = chartState.treatments,
                    timeRangeHours = chartState.rangeHours,
                    currentBgValue = chartState.currentBgValue,
                    lowLine = chartState.lowLine,
                    highLine = chartState.highLine,
                    predictions = chartState.predictions,
                    historyFraction = chartState.historyFraction,
                    axisMinValue = chartState.axisMinValue,
                    axisHeadroom = chartState.axisHeadroom,
                    formatValue = { v -> graphViewModel.formatBgChartAxisTick(v.toDouble()) },
                    isDark = isDark,
                )
                IobChartCard(
                    iobReadings = chartState.iobReadings,
                    currentIob = chartState.currentIob,
                    timeRangeHours = chartState.rangeHours,
                    historyFraction = chartState.historyFraction,
                    isDark = isDark,
                )
                TimeFilterBar(
                    selectedHours = rangeHours,
                    onSelectHours = { rangeHours = it },
                    onOpenStats = commands::openStatsScreen,
                    onOpenTreatment = commands::openTreatmentsScreen,
                    statsLabel = stringResource(R.string.stats_button),
                    treatmentLabel = stringResource(app.aaps.core.ui.R.string.overview_treatment_label),
                    isDark = isDark,
                )
                if (chartState.pumpStatusText.isNotBlank()) {
                    PumpStatusNotification(status = chartState.pumpStatusText, isDark = isDark)
                }
                if (embeddedState.notifications.isNotEmpty()) {
                    NotificationsSection(
                        notifications = embeddedState.notifications,
                        isDark = isDark,
                        onDismiss = { id -> embeddedState.onDismissNotification?.invoke(id) },
                    )
                }
            }
            if (showTools) {
                BackHandler(enabled = showTools) { showTools = false }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                if (isDark) {
                                    listOf(Color(0xFF070E1B), Color(0xFF0B1424), Color(0xFF070E1B))
                                } else {
                                    listOf(Color(0xFFF1F5F9), Color(0xFFE8EEF8), Color(0xFFF1F5F9))
                                }
                            )
                        )
                ) {
                    DashboardV2ToolsScreen(
                        paddingValues = PaddingValues(0.dp),
                        fabBottomOffset = 0.dp,
                        availablePluginClassNames = availablePluginClassNames,
                        onAction = { action ->
                            showTools = false
                            onToolAction(action)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

internal fun buildGlassChartState(
    rangeHours: Int,
    nowEpochMs: Long,
    bgReadings: List<BgDataPoint>,
    iobPoints: List<GraphDataPoint>,
    boluses: List<BolusGraphPoint>,
    carbs: List<CarbsGraphPoint>,
    chartConfig: ChartConfig,
    mgdlToChartY: (Double) -> Double,
    pumpStatusText: String,
    predictions: List<BgDataPoint>,
    predictionHorizonHours: Int = 2,
): GlassChartState {
    val windowStart = nowEpochMs - rangeHours * 3_600_000L
    fun progress(timestamp: Long): Float =
        ((timestamp - windowStart).toFloat() / (nowEpochMs - windowStart).toFloat()).coerceIn(0f, 1f)

    val windowedBg = bgReadings.filter { it.timestamp in windowStart..nowEpochMs }.sortedBy { it.timestamp }
    val windowedIob = iobPoints.filter { it.timestamp in windowStart..nowEpochMs }.sortedBy { it.timestamp }
    val windowedBoluses = boluses.filter { it.timestamp in windowStart..nowEpochMs }
    val windowedCarbs = carbs.filter { it.timestamp in windowStart..nowEpochMs }

    val predictionWindowEnd = nowEpochMs + predictionHorizonHours * 3_600_000L
    val windowedPredictions = predictions
        .filter { it.timestamp in nowEpochMs..predictionWindowEnd }
        .sortedBy { it.timestamp }

    fun predictionProgress(timestamp: Long): Float =
        ((timestamp - nowEpochMs).toFloat() / (predictionWindowEnd - nowEpochMs).toFloat()).coerceIn(0f, 1f)

    fun predictionType(type: BgType): PredictionType? = when (type) {
        BgType.IOB_PREDICTION   -> PredictionType.IOB
        BgType.COB_PREDICTION   -> PredictionType.COB
        BgType.A_COB_PREDICTION -> PredictionType.A_COB
        BgType.UAM_PREDICTION   -> PredictionType.UAM
        BgType.ZT_PREDICTION    -> PredictionType.ZT
        else                    -> null
    }

    val predictionPoints = windowedPredictions.mapNotNull { p ->
        val type = predictionType(p.type) ?: return@mapNotNull null
        PredictionPoint(
            progress = predictionProgress(p.timestamp),
            value = mgdlToChartY(p.value).toFloat(),
            type = type,
        )
    }

    val historyFraction = rangeHours.toFloat() / (rangeHours + predictionHorizonHours).toFloat()

    // BgDataPoint.value is always mg/dL; chartConfig.lowMark/highMark are already in the user's DISPLAY
    // unit (UnitDoubleKey preferences are unit-aware). Convert the readings to display-unit space via
    // mgdlToChartY so everything drawn (curve, low/high band, current value) agrees with what
    // formatValue() will show — do not mix mg/dL and display-unit values in the same chart.
    val bgReadingPoints = windowedBg.map {
        BgReadingPoint(progress = progress(it.timestamp), value = mgdlToChartY(it.value).toFloat())
    }
    val iobReadingPoints = windowedIob.map {
        IobReadingPoint(progress = progress(it.timestamp), iob = it.value.toFloat())
    }
    val treatmentPoints = buildList {
        windowedBoluses.forEach { b ->
            add(TreatmentPoint(progress = progress(b.timestamp), isCarb = false, label = b.label, timestamp = b.timestamp))
        }
        windowedCarbs.forEach { c ->
            add(TreatmentPoint(progress = progress(c.timestamp), isCarb = true, label = c.label, timestamp = c.timestamp))
        }
    }

    return GlassChartState(
        bgReadings = bgReadingPoints,
        iobReadings = iobReadingPoints,
        treatments = treatmentPoints,
        predictions = predictionPoints,
        historyFraction = historyFraction,
        currentBgValue = bgReadingPoints.lastOrNull()?.value ?: 0f,
        currentIob = iobReadingPoints.lastOrNull()?.iob ?: 0f,
        lowLine = chartConfig.lowMark.toFloat(),
        highLine = chartConfig.highMark.toFloat(),
        axisMinValue = mgdlToChartY(30.0).toFloat(),
        axisHeadroom = mgdlToChartY(40.0).toFloat() - mgdlToChartY(0.0).toFloat(),
        rangeHours = rangeHours,
        pumpStatusText = pumpStatusText,
    )
}

internal fun buildGlassUiState(status: StatusCardState?, lights: StatusUiState?): GlassUiState {
    if (status == null) return GlassUiState()
    return GlassUiState(
        currentBg = status.glucoseText,
        unit = status.unitText,
        glucoseColor = status.glucoseColor,
        deltaText = status.deltaText,
        trendArrowRes = status.trendArrowRes,
        timeAgo = status.timeAgo,
        insulinAge = lights?.insulinStatus?.age ?: "--",
        insulinAgeStatus = lights?.insulinStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        insulinLabel = lights?.insulinStatus?.label ?: "Insulin",
        cannulaAge = lights?.cannulaStatus?.age ?: "--",
        cannulaAgeStatus = lights?.cannulaStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        cannulaLabel = lights?.cannulaStatus?.label ?: "Cannula",
        batteryAge = lights?.batteryStatus?.age ?: "--",
        batteryAgeStatus = lights?.batteryStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        batteryLabel = lights?.batteryStatus?.label ?: "Battery",
        sensorAge = lights?.sensorStatus?.age ?: "--",
        sensorAgeStatus = lights?.sensorStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        sensorLabel = lights?.sensorStatus?.label ?: "Sensor",
        loopStatusText = status.loopStatusText,
        loopIsRunning = status.loopIsRunning,
        iobText = status.iobText,
        isTempTargetActive = status.isTempTargetActive,
        targetText = status.targetText ?: "--",
        basalPercentText = status.effectiveBasalText ?: "--",
        stepsText = status.stepsText ?: "--",
        hrText = status.hrText ?: "--",
    )
}
