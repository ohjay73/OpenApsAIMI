package app.aaps.plugins.main.general.dashboard

import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.dashboard.GlucoseHeroRing
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.compose.DashboardComposeHeroUiMapper
import app.aaps.plugins.main.general.dashboard.compose.DashboardGraphComposeCard
import app.aaps.plugins.main.general.dashboard.compose.DashboardNotificationsComposeList
import app.aaps.plugins.main.general.dashboard.compose.LocalDashboardHeroCommands
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import kotlinx.coroutines.delay

/** Compact, status-first dashboard variant. All displayed values come from the shared shell state. */
@Composable
internal fun DashboardV2ComposeEmbedded(
    shellPostRoot: View,
    embeddedState: DashboardEmbeddedComposeState,
    viewModel: OverviewViewModel,
    graphViewModel: GraphViewModel,
    onShellBindingReady: (DashboardShellBinding) -> Unit,
    modifier: Modifier = Modifier,
) {
    var auditorHost by remember { mutableStateOf<FrameLayout?>(null) }
    LaunchedEffect(shellPostRoot, auditorHost) {
        val host = auditorHost ?: return@LaunchedEffect
        onShellBindingReady(
            DashboardShellBinding.fromComposeEmbeddedColumn(
                shellPostRoot = shellPostRoot,
                auditorHost = host,
                glucoseGraph = null,
            ),
        )
    }

    AapsTheme {
        val status by viewModel.statusCardState.observeAsState()
        val commands = LocalDashboardHeroCommands.current
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val contentPadding = 12.dp
        val sectionSpacing = 8.dp
        val graphCard: @Composable (Modifier) -> Unit = { graphModifier ->
            DashboardGraphComposeCard(
                composeState = embeddedState,
                graphViewModel = graphViewModel,
                attachLegacyGraphBackend = false,
                hideDetailedGraphStatus = true,
                expandGraphVertically = true,
                graphContent = {},
                modifier = graphModifier,
            )
        }
        val statusCard: @Composable (Modifier) -> Unit = { statusModifier ->
            DashboardV2StatusCard(
                state = status,
                compact = isLandscape,
                onLoopClick = commands::openLoopDialogFromHero,
                onAuditorHostAttached = { host ->
                    if (auditorHost !== host) auditorHost = host
                },
                modifier = statusModifier,
            )
        }

        if (isLandscape) {
            Row(
                modifier = modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(sectionSpacing),
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                ) {
                    statusCard(Modifier.fillMaxWidth())
                    DashboardNotificationsComposeList(
                        composeState = embeddedState,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                graphCard(
                    Modifier
                        .weight(0.6f)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing),
            ) {
                statusCard(Modifier.fillMaxWidth())
                DashboardNotificationsComposeList(
                    composeState = embeddedState,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                graphCard(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DashboardV2StatusCard(
    state: StatusCardState?,
    compact: Boolean,
    onLoopClick: () -> Unit,
    onAuditorHostAttached: (FrameLayout) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.dashboard_v2_value_unavailable)
    val warmupHeroActive = state?.let {
        it.warmup?.active == true && (it.glucoseMgdl == null || !it.isGlucoseActual)
    } == true
    var warmupSecondTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(warmupHeroActive) {
        if (warmupHeroActive) {
            while (true) {
                warmupSecondTick = System.currentTimeMillis() / 1_000L
                delay(1_000L)
            }
        } else {
            warmupSecondTick = 0L
        }
    }
    val heroState = remember(context, state, warmupSecondTick) {
        state?.let { DashboardComposeHeroUiMapper.buildHeroState(context, it) }
    }
    val loopText = state?.loopStatusText.dashboardV2ValueOr(unavailable)
    val loopDescription = stringResource(R.string.dashboard_v2_loop_a11y, loopText)
    val openLoopLabel = stringResource(R.string.dashboard_v2_open_loop)
    val heroDescription = state?.contentDescription.dashboardV2ValueOr(
        stringResource(R.string.dashboard_v2_glucose_unavailable_a11y),
    )
    val heroSize = if (compact) 116.dp else 136.dp

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 10.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.dashboard_v2_current_status),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = loopDescription }
                        .clickable(
                            onClickLabel = openLoopLabel,
                            role = Role.Button,
                            onClick = onLoopClick,
                        ),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = loopText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier.size(heroSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (heroState != null) {
                        GlucoseHeroRing(
                            state = heroState,
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics { contentDescription = heroDescription }
                                .clickable(
                                    onClickLabel = openLoopLabel,
                                    role = Role.Button,
                                    onClick = onLoopClick,
                                ),
                        )
                    } else {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics { contentDescription = heroDescription },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.dashboard_v2_glucose_unavailable),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    AndroidView(
                        factory = { FrameLayout(it) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(28.dp),
                        update = onAuditorHostAttached,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DashboardV2Badge(
                            label = stringResource(R.string.dashboard_v2_reservoir),
                            value = state?.reservoirText.dashboardV2ValueOr(unavailable),
                            modifier = Modifier.weight(1f),
                        )
                        DashboardV2Badge(
                            label = stringResource(R.string.dashboard_v2_battery),
                            value = state?.pumpBatteryText.dashboardV2ValueOr(unavailable),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DashboardV2Badge(
                            label = stringResource(R.string.dashboard_v2_sensor),
                            value = state?.sensorAgeText.dashboardV2ValueOr(unavailable),
                            modifier = Modifier.weight(1f),
                        )
                        DashboardV2Badge(
                            label = stringResource(R.string.dashboard_v2_site),
                            value = state?.infusionAgeText.dashboardV2ValueOr(unavailable),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DashboardV2Metric(
                    label = stringResource(R.string.dashboard_v2_iob),
                    value = state?.iobText.dashboardV2ValueOr(unavailable),
                    modifier = Modifier.weight(1f),
                )
                DashboardV2Metric(
                    label = stringResource(R.string.dashboard_v2_target),
                    value = state?.targetText.dashboardV2ValueOr(unavailable),
                    modifier = Modifier.weight(1f),
                )
                DashboardV2Metric(
                    label = stringResource(R.string.dashboard_v2_basal),
                    value = state?.effectiveBasalText.dashboardV2ValueOr(unavailable),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DashboardV2Badge(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    DashboardV2LabelValue(
        label = label,
        value = value,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
    )
}

@Composable
private fun DashboardV2Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    DashboardV2LabelValue(
        label = label,
        value = value,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    )
}

@Composable
private fun DashboardV2LabelValue(
    label: String,
    value: String,
    containerColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.dashboard_v2_metric_a11y, label, value)
    Surface(
        modifier = modifier.semantics { contentDescription = description },
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun String?.dashboardV2ValueOr(fallback: String): String =
    this?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
