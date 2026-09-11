package app.aaps.plugins.main.general.dashboard.glass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.TreatmentGraphData
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
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
    var showPersonalize by remember { mutableStateOf(false) }
    LaunchedEffect(showTools) { if (!showTools) showPersonalize = false }

    AapsTheme {
        val preferences = LocalPreferences.current
        val darkModeValue by preferences.observe(StringKey.GeneralDarkMode).collectAsState()
        val isDark = when (UiMode.fromString(darkModeValue)) {
            UiMode.LIGHT  -> false
            UiMode.DARK   -> true
            UiMode.SYSTEM -> isSystemInDarkTheme()
        }
        val selectedPillsRaw by preferences.observe(StringNonKey.GlassSelectedPills).collectAsState()
        val selectedPills = remember(selectedPillsRaw) { parseSelectedGlassPills(selectedPillsRaw) }
        LaunchedEffect(Unit) {
            if (!preferences.get(BooleanNonKey.GlassTopGridPillsMigrated)) {
                val current = parseSelectedGlassPills(preferences.get(StringNonKey.GlassSelectedPills)).toSet()
                preferences.put(StringNonKey.GlassSelectedPills, serializeSelectedGlassPills(withTopGridDefaultsMerged(current)))
                preferences.put(BooleanNonKey.GlassTopGridPillsMigrated, true)
            }
        }
        val selectedToolsRaw by preferences.observe(StringNonKey.GlassSelectedTools).collectAsState()
        val selectedTools = remember(selectedToolsRaw) { parseSelectedGlassTools(selectedToolsRaw).toSet() }
        val treatmentData by graphViewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
        val state = buildGlassUiState(status, statusLights, treatmentData)

        val bgReadings by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()
        val iobData by graphViewModel.iobGraphFlow.collectAsStateWithLifecycle()
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
                    selectedPills = selectedPills,
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
                BackHandler(enabled = showTools) {
                    if (showPersonalize) showPersonalize = false else showTools = false
                }
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
                    if (showPersonalize) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(R.string.dashboard_glass_personalize_title),
                                    color = if (isDark) Color.White else Color(0xFF0D1B2A),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(app.aaps.core.ui.R.string.ok),
                                    color = if (isDark) Color(0xFF6DFAD2) else Color(0xFF00B894),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            onClickLabel = stringResource(app.aaps.core.ui.R.string.ok),
                                            role = Role.Button,
                                        ) { showPersonalize = false }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                            GlassPersonalizeScreen(
                                selectedPills = selectedPills.toSet(),
                                onToggle = { id, checked ->
                                    val next = selectedPills.toMutableSet()
                                    if (checked) next.add(id) else next.remove(id)
                                    preferences.put(StringNonKey.GlassSelectedPills, serializeSelectedGlassPills(next))
                                },
                                selectedTools = selectedTools,
                                onToolToggle = { action, checked ->
                                    val next = selectedTools.toMutableSet()
                                    if (checked) next.add(action) else next.remove(action)
                                    preferences.put(StringNonKey.GlassSelectedTools, serializeSelectedGlassTools(next))
                                },
                                isDark = isDark,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Text(
                                    text = stringResource(R.string.dashboard_glass_personalize_button),
                                    color = if (isDark) Color(0xFF6DFAD2) else Color(0xFF00B894),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            onClickLabel = stringResource(R.string.dashboard_glass_personalize_button),
                                            role = Role.Button,
                                        ) { showPersonalize = true }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                            DashboardV2ToolsScreen(
                                paddingValues = PaddingValues(0.dp),
                                fabBottomOffset = 0.dp,
                                availablePluginClassNames = availablePluginClassNames,
                                onAction = { action ->
                                    showTools = false
                                    onToolAction(action)
                                },
                                modifier = Modifier.fillMaxSize(),
                                visibleActions = selectedTools,
                            )
                        }
                    }
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

    val predictionPointsRaw = windowedPredictions.mapNotNull { p ->
        val type = predictionType(p.type) ?: return@mapNotNull null
        PredictionPoint(
            progress = predictionProgress(p.timestamp),
            value = mgdlToChartY(p.value).toFloat(),
            type = type,
        )
    }
    // Anchor each prediction path to the last real BG reading at progress=0, so the dashed prediction
    // line starts exactly where the solid history line ends. Without this, predictions (timestamped from
    // the last loop run, not from "now", and missing their own index-0 anchor point in the underlying
    // AdvancedPredictionCurves/predictionsAsGv data) can visibly jump from the freshest CGM reading.
    // A real prediction point can itself land at progress=0 (its timestamp equals nowEpochMs) — drop those
    // before prepending the synthetic anchor so a type never ends up with two progress=0 points (which would
    // just move the discontinuity instead of removing it, since the two values are not guaranteed equal).
    val lastReadingY = windowedBg.lastOrNull()?.let { mgdlToChartY(it.value).toFloat() }
    val predictionPoints = if (lastReadingY != null) {
        val anchors = predictionPointsRaw.map { it.type }.distinct().map { type ->
            PredictionPoint(progress = 0f, value = lastReadingY, type = type)
        }
        anchors + predictionPointsRaw.filter { it.progress > 0f }
    } else {
        predictionPointsRaw
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

internal fun buildGlassUiState(
    status: StatusCardState?,
    lights: StatusUiState?,
    treatmentData: TreatmentGraphData,
): GlassUiState {
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
        // lastSensorValueText holds the total IOB WITHOUT the "IOB: " prefix baked into status.iobText
        // (which DASHBOARD_V2/legacy views display standalone) — reused here to avoid Glass's own separate
        // pill title duplicating that prefix. Do not "fix" this back to status.iobText.
        iobText = status.lastSensorValueText ?: "--",
        isTempTargetActive = status.isTempTargetActive,
        targetText = status.targetText ?: "--",
        basalPercentText = status.effectiveBasalText ?: "--",
        stepsText = status.stepsText ?: "--",
        hrText = status.hrText ?: "--",
        lastBolusText = treatmentData.boluses.filter { it.isValid && it.timestamp <= System.currentTimeMillis() }.maxByOrNull { it.timestamp }?.label ?: "--",
        lastCarbsText = treatmentData.carbs.filter { it.isValid && it.timestamp <= System.currentTimeMillis() }.maxByOrNull { it.timestamp }?.label ?: "--",
    )
}
