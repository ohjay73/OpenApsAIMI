@file:Suppress("ktlint:standard:function-naming")

package app.aaps.ui.compose.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.ui.R

/** Stable five-item navigation used only by the DASHBOARD_V2 home shell. */
enum class DashboardV2NavigationTab(
    val elementType: ElementType? = null,
) {
    MAIN,
    TOOLS,
    BOLUS(ElementType.INSULIN),
    CONFIGURATION(ElementType.CONFIGURATION),
    PREFERENCES(ElementType.SETTINGS),
    ;

    companion object {
        val fixedOrder: List<DashboardV2NavigationTab> =
            listOf(MAIN, TOOLS, BOLUS, CONFIGURATION, PREFERENCES)
    }
}

@Composable
fun DashboardV2NavigationBar(
    selectedTab: DashboardV2NavigationTab,
    masterOrPairedClient: Boolean,
    onTabClick: (DashboardV2NavigationTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors =
        NavigationBarItemDefaults.colors(
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
        DashboardV2NavigationTab.fixedOrder.forEach { tab ->
            val enabled = tab != DashboardV2NavigationTab.BOLUS || masterOrPairedClient
            NavigationBarItem(
                selected = tab == selectedTab,
                onClick = { onTabClick(tab) },
                enabled = enabled,
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
    }
}

private fun DashboardV2NavigationTab.icon(): ImageVector =
    when (this) {
        DashboardV2NavigationTab.MAIN -> Icons.Default.Home
        DashboardV2NavigationTab.TOOLS -> Icons.Default.Build
        DashboardV2NavigationTab.BOLUS -> Icons.Default.Medication
        DashboardV2NavigationTab.CONFIGURATION -> Icons.Default.Tune
        DashboardV2NavigationTab.PREFERENCES -> Icons.Default.Settings
    }

private fun DashboardV2NavigationTab.labelRes(): Int =
    when (this) {
        DashboardV2NavigationTab.MAIN -> R.string.dashboard_v2_nav_main
        DashboardV2NavigationTab.TOOLS -> R.string.dashboard_v2_nav_tools
        DashboardV2NavigationTab.BOLUS -> R.string.dashboard_v2_nav_bolus
        DashboardV2NavigationTab.CONFIGURATION -> R.string.dashboard_v2_nav_configuration
        DashboardV2NavigationTab.PREFERENCES -> R.string.dashboard_v2_nav_preferences
    }
