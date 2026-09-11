@file:Suppress("ktlint:standard:function-naming")

package app.aaps.ui.compose.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.icons.IcAutomation
import app.aaps.core.ui.compose.icons.IcProfile
import app.aaps.core.ui.compose.icons.Pump
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.ui.R
import app.aaps.core.ui.R as CoreUiR

/** Stable six-item navigation used only by the Glass home shell. */
enum class GlassNavigationTab {
    TREATMENTS, PROFILES, BOLUS, PUMP, SCENARIOS, MANAGEMENT;

    companion object {
        val fixedOrder: List<GlassNavigationTab> = listOf(TREATMENTS, PROFILES, BOLUS, PUMP, SCENARIOS, MANAGEMENT)
    }
}

// Same rule MainNavigationBar applies to Treatments/Scenes, and DashboardV2NavigationBar applies to its own
// Bolus tab (ElementType.BOLUS_WIZARD is MASTER_OR_PAIRED_CLIENT-visible; a non-paired client has no channel
// to actually deliver a dose, so the calculator must not be reachable). Hoisted so it isn't rebuilt every
// recomposition.
internal val MASTER_ONLY_TABS = setOf(GlassNavigationTab.TREATMENTS, GlassNavigationTab.SCENARIOS, GlassNavigationTab.BOLUS)

// glass_pump_detail has no ElementType of its own (it's a Glass-only read-only status screen, not a shared
// destination), so it's reached via NavigationRequest.Route, which — unlike NavigationRequest.Element — is
// NOT run through navigateProtected/ProtectionCheck. Only ever point this at a read-only screen with no
// therapy-affecting action; never repurpose it for a route that an ElementType's protection level would
// normally gate (bolus, settings, pump commands, etc.).
private const val GLASS_PUMP_DETAIL_ROUTE = "glass_pump_detail"

@Composable
fun GlassNavigationBar(
    masterOrPairedClient: Boolean,
    onTreatmentClick: () -> Unit,
    onScenariosClick: () -> Unit,
    onManagementClick: () -> Unit,
    onNavigate: (NavigationRequest) -> Unit,
    modifier: Modifier = Modifier,
    /** True only while an open-loop suggestion is waiting for the user to accept it — same signal
     *  MainNavigationBar's own loop-accept button uses. There is no other path to this action in the
     *  Glass Compose UI, so it must stay reachable here even though the other 6 tabs are fixed. */
    loopActionAvailable: Boolean = false,
    onLoopActionClick: () -> Unit = {},
) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        windowInsets = WindowInsets(0),
        modifier = modifier,
    ) {
        GlassNavigationTab.fixedOrder.forEach { tab ->
            val enabled = masterOrPairedClient || tab !in MASTER_ONLY_TABS
            NavigationBarItem(
                selected = false,
                enabled = enabled,
                onClick = {
                    when (tab) {
                        GlassNavigationTab.TREATMENTS -> onTreatmentClick()
                        GlassNavigationTab.SCENARIOS   -> onScenariosClick()
                        GlassNavigationTab.MANAGEMENT  -> onManagementClick()
                        GlassNavigationTab.PROFILES    -> onNavigate(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT))
                        GlassNavigationTab.BOLUS        -> onNavigate(NavigationRequest.Element(ElementType.BOLUS_WIZARD))
                        GlassNavigationTab.PUMP         -> onNavigate(NavigationRequest.Route(GLASS_PUMP_DETAIL_ROUTE))
                    }
                },
                icon = {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = stringResource(tab.labelRes()),
                    )
                },
                label = { Text(text = stringResource(tab.labelRes())) },
                colors = colors,
            )
        }
        // Loop accept action (visible only when AAPS has a pending suggestion in open loop) — the fixed 6
        // tabs above cover the mockup's steady-state navigation, but this transient action has no other
        // path in the Glass Compose UI (not the drawer, not the Management sheet), so it must stay
        // reachable as a 7th item when it applies, matching MainNavigationBar's own loop-accept button.
        if (loopActionAvailable) {
            NavigationBarItem(
                selected = false,
                onClick = onLoopActionClick,
                icon = {
                    BadgedBox(
                        badge = {
                            Badge(containerColor = AapsTheme.generalColors.statusNormal, contentColor = Color.Black) {
                                Text(text = "1")
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = stringResource(R.string.loop_accept_nav_label),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                label = { Text(text = stringResource(R.string.loop_accept_nav_label)) },
                colors = colors,
            )
        }
    }
}

private fun GlassNavigationTab.icon(): ImageVector =
    when (this) {
        GlassNavigationTab.TREATMENTS -> Icons.Default.Fastfood
        GlassNavigationTab.PROFILES   -> IcProfile
        GlassNavigationTab.BOLUS      -> Icons.Default.Medication
        GlassNavigationTab.PUMP       -> Pump
        GlassNavigationTab.SCENARIOS  -> IcAutomation
        GlassNavigationTab.MANAGEMENT -> Icons.Default.ManageAccounts
    }

private fun GlassNavigationTab.labelRes(): Int =
    when (this) {
        GlassNavigationTab.TREATMENTS -> CoreUiR.string.treatments
        GlassNavigationTab.PROFILES   -> R.string.dashboard_glass_nav_profiles
        GlassNavigationTab.BOLUS      -> R.string.dashboard_v2_nav_bolus
        GlassNavigationTab.PUMP       -> CoreUiR.string.pump
        GlassNavigationTab.SCENARIOS  -> CoreUiR.string.scenes
        GlassNavigationTab.MANAGEMENT -> CoreUiR.string.manage
    }
