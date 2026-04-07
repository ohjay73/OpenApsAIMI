package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.decimalPlaces
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.step
import app.aaps.core.keys.unitLabelResId
import app.aaps.core.keys.valueResId
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.SliderWithButtons
import app.aaps.core.ui.compose.preference.AdaptiveDoublePreferenceItem
import app.aaps.core.ui.compose.preference.AdaptiveSwitchPreferenceItem
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R
import java.text.DecimalFormat
import kotlinx.coroutines.launch

/**
 * Full-screen Compose PK/PD setup: presets, comfort, learning bounds, and expert parameters.
 */
@Composable
fun AimiPkpdSettingsScreen(
    preferences: Preferences,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var preferenceRevision by remember { mutableIntStateOf(0) }
    var selectedPreset by remember { mutableStateOf(PkpdInsulinPreset.CUSTOM) }

    ProvidePreferenceTheme {
        CompositionLocalProvider(LocalPreferences provides preferences) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    AapsTopAppBar(
                        title = { Text(stringResource(R.string.aimi_pkpd_compose_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(app.aaps.core.ui.R.string.back),
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                val context = LocalContext.current
                val presetAppliedMessage = context.getString(R.string.aimi_pkpd_preset_applied)
                val syncDoneMessage = context.getString(R.string.aimi_pkpd_sync_done)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                ) {
                    LoopSummaryCard(preferences)

                    AdaptiveSwitchPreferenceItem(
                        booleanKey = BooleanKey.OApsAIMIPkpdEnabled,
                        titleResId = R.string.oaps_aimi_pkpd_enabled_title,
                        summaryResId = R.string.oaps_aimi_pkpd_enabled_summary,
                    )

                    Text(
                        stringResource(R.string.aimi_pkpd_preset_section_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.aimi_pkpd_compose_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val chipScroll = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(chipScroll),
                        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
                    ) {
                        PresetChip(
                            label = stringResource(R.string.aimi_pkpd_preset_ultra_fast),
                            selected = selectedPreset == PkpdInsulinPreset.ULTRA_FAST,
                            onClick = {
                                applyPkpdInsulinPreset(preferences, PkpdInsulinPreset.ULTRA_FAST)
                                selectedPreset = PkpdInsulinPreset.ULTRA_FAST
                                preferenceRevision++
                                scope.launch {
                                    snackbarHostState.showSnackbar(presetAppliedMessage)
                                }
                            },
                        )
                        PresetChip(
                            label = stringResource(R.string.aimi_pkpd_preset_rapid),
                            selected = selectedPreset == PkpdInsulinPreset.RAPID,
                            onClick = {
                                applyPkpdInsulinPreset(preferences, PkpdInsulinPreset.RAPID)
                                selectedPreset = PkpdInsulinPreset.RAPID
                                preferenceRevision++
                                scope.launch {
                                    snackbarHostState.showSnackbar(presetAppliedMessage)
                                }
                            },
                        )
                        PresetChip(
                            label = stringResource(R.string.aimi_pkpd_preset_standard),
                            selected = selectedPreset == PkpdInsulinPreset.STANDARD,
                            onClick = {
                                applyPkpdInsulinPreset(preferences, PkpdInsulinPreset.STANDARD)
                                selectedPreset = PkpdInsulinPreset.STANDARD
                                preferenceRevision++
                                scope.launch {
                                    snackbarHostState.showSnackbar(presetAppliedMessage)
                                }
                            },
                        )
                        PresetChip(
                            label = stringResource(R.string.aimi_pkpd_preset_custom),
                            selected = selectedPreset == PkpdInsulinPreset.CUSTOM,
                            onClick = { selectedPreset = PkpdInsulinPreset.CUSTOM },
                        )
                    }

                    TextButton(
                        onClick = {
                            syncPkpdLearnedStateToBounds(preferences)
                            preferenceRevision++
                            scope.launch {
                                snackbarHostState.showSnackbar(syncDoneMessage)
                            }
                        },
                        modifier = Modifier.align(Alignment.Start),
                    ) {
                        Text(stringResource(R.string.aimi_pkpd_sync_state_action))
                    }

                    ExpandableSection(title = stringResource(R.string.aimi_pkpd_section_comfort), initiallyExpanded = true) {
                        AdaptiveDoublePreferenceItem(
                            doubleKey = DoubleKey.OApsAIMIIsfFusionMinFactor,
                            titleResId = R.string.oaps_aimi_isf_fusion_min_title,
                        )
                        AdaptiveDoublePreferenceItem(
                            doubleKey = DoubleKey.OApsAIMIIsfFusionMaxFactor,
                            titleResId = R.string.oaps_aimi_isf_fusion_max_title,
                        )
                        AdaptiveDoublePreferenceItem(
                            doubleKey = DoubleKey.OApsAIMIIsfFusionMaxChangePerTick,
                            titleResId = R.string.oaps_aimi_isf_fusion_slope_title,
                        )
                        AdaptiveDoublePreferenceItem(
                            doubleKey = DoubleKey.OApsAIMISmbTailThreshold,
                            titleResId = R.string.oaps_aimi_smb_tail_threshold_title,
                        )
                        AdaptiveDoublePreferenceItem(
                            doubleKey = DoubleKey.OApsAIMISmbTailDamping,
                            titleResId = R.string.oaps_aimi_smb_tail_damping_title,
                        )
                        AdaptiveDoublePreferenceItem(
                            doubleKey = DoubleKey.OApsAIMISmbExerciseDamping,
                            titleResId = R.string.oaps_aimi_smb_exercise_damping_title,
                        )
                        AdaptiveDoublePreferenceItem(
                            doubleKey = DoubleKey.OApsAIMISmbLateFatDamping,
                            titleResId = R.string.oaps_aimi_smb_late_fat_damping_title,
                        )
                    }

                    ExpandableSection(title = stringResource(R.string.aimi_pkpd_section_learning), initiallyExpanded = false) {
                        PkpdReactiveDoubleSlider(
                            key = DoubleKey.OApsAIMIPkpdInitialDiaH,
                            titleResId = R.string.oaps_aimi_pkpd_initial_dia_title,
                            preferenceRevision = preferenceRevision,
                        )
                        PkpdReactiveDoubleSlider(
                            key = DoubleKey.OApsAIMIPkpdInitialPeakMin,
                            titleResId = R.string.oaps_aimi_pkpd_initial_peak_title,
                            preferenceRevision = preferenceRevision,
                        )
                        PkpdReactiveDoubleSlider(
                            key = DoubleKey.OApsAIMIPkpdBoundsDiaMinH,
                            titleResId = R.string.oaps_aimi_pkpd_dia_min_title,
                            preferenceRevision = preferenceRevision,
                        )
                        PkpdReactiveDoubleSlider(
                            key = DoubleKey.OApsAIMIPkpdBoundsDiaMaxH,
                            titleResId = R.string.oaps_aimi_pkpd_dia_max_title,
                            preferenceRevision = preferenceRevision,
                        )
                        PkpdReactiveDoubleSlider(
                            key = DoubleKey.OApsAIMIPkpdBoundsPeakMinMin,
                            titleResId = R.string.oaps_aimi_pkpd_peak_min_title,
                            preferenceRevision = preferenceRevision,
                        )
                        PkpdReactiveDoubleSlider(
                            key = DoubleKey.OApsAIMIPkpdBoundsPeakMinMax,
                            titleResId = R.string.oaps_aimi_pkpd_peak_max_title,
                            preferenceRevision = preferenceRevision,
                        )
                        PkpdReactiveDoubleSlider(
                            key = DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH,
                            titleResId = R.string.oaps_aimi_pkpd_max_dia_delta_title,
                            preferenceRevision = preferenceRevision,
                        )
                        PkpdReactiveDoubleSlider(
                            key = DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin,
                            titleResId = R.string.oaps_aimi_pkpd_max_peak_delta_title,
                            preferenceRevision = preferenceRevision,
                        )
                    }

                    ExpandableSection(title = stringResource(R.string.aimi_pkpd_section_expert), initiallyExpanded = false) {
                        AdaptiveSwitchPreferenceItem(
                            booleanKey = BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled,
                            titleResId = R.string.oaps_aimi_pkpd_relief_enabled_title,
                            summaryResId = R.string.oaps_aimi_pkpd_relief_enabled_summary,
                        )
                        AdaptiveDoublePreferenceItem(
                            doubleKey = DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor,
                            titleResId = R.string.oaps_aimi_pkpd_relief_factor_title,
                        )
                        AdaptiveDoublePreferenceItem(
                            doubleKey = DoubleKey.OApsAIMIRedCarpetRestoreThreshold,
                            titleResId = R.string.oaps_aimi_redcarpet_restore_title,
                        )
                        AdaptiveSwitchPreferenceItem(
                            booleanKey = BooleanKey.OApsAIMIIobSurveillanceGuard,
                            titleResId = R.string.aimi_iob_surveillance_guard_title,
                            summaryResId = R.string.aimi_iob_surveillance_guard_summary,
                        )
                        AdaptiveDoublePreferenceItem(
                            doubleKey = DoubleKey.OApsAIMIPriorityMaxIobFactor,
                            titleResId = R.string.oaps_aimi_priority_max_iob_factor_title,
                        )
                        AdaptiveDoublePreferenceItem(
                            doubleKey = DoubleKey.OApsAIMIPriorityMaxIobExtraU,
                            titleResId = R.string.oaps_aimi_priority_max_iob_extra_title,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoopSummaryCard(preferences: Preferences) {
    val dia = preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH)
    val peak = preferences.get(DoubleKey.OApsAIMIPkpdStatePeakMin)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(AapsSpacing.medium)) {
            Text(
                stringResource(R.string.aimi_pkpd_loop_summary_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.aimi_pkpd_loop_summary_format, dia, peak),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = AapsSpacing.small),
            )
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun ExpandableSection(
    title: String,
    initiallyExpanded: Boolean,
    content: @Composable () -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = AapsSpacing.small),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun PkpdReactiveDoubleSlider(
    key: DoubleKey,
    titleResId: Int,
    preferenceRevision: Int,
) {
    val preferences = LocalPreferences.current
    val summaryRes = key.summaryResId?.takeIf { it != 0 }?.let { stringResource(it) }
    val unitType = key.unitType
    val decimalPlaces = unitType.decimalPlaces()
    val step = unitType.step()
    val valueFormatResId = unitType.valueResId()
    val valueFormat = if (decimalPlaces == 0) DecimalFormat("0") else DecimalFormat("0.${"0".repeat(decimalPlaces)}")
    val unitLabelResId = unitType.unitLabelResId()
    val unitLabel = unitLabelResId?.takeIf { it != 0 }?.let { stringResource(it) } ?: ""

    var local by remember(key) { mutableDoubleStateOf(preferences.get(key)) }
    LaunchedEffect(key, preferenceRevision) {
        local = preferences.get(key)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AapsSpacing.extraSmall),
    ) {
        Text(stringResource(titleResId), style = MaterialTheme.typography.titleSmall)
        summaryRes?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SliderWithButtons(
            value = local,
            onValueChange = {
                val clamped = it.coerceIn(key.min, key.max)
                local = clamped
                preferences.put(key, clamped)
            },
            valueRange = key.min..key.max,
            step = step,
            showValue = true,
            valueFormatResId = valueFormatResId,
            valueFormat = valueFormat,
            unitLabel = unitLabel,
            dialogLabel = stringResource(titleResId),
            dialogSummary = summaryRes,
        )
    }
}
