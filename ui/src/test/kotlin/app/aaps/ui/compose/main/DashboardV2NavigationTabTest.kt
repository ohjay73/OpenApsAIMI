package app.aaps.ui.compose.main

import app.aaps.core.interfaces.navigation.ElementType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DashboardV2NavigationTabTest {

    @Test
    fun `navigation exposes the fixed five tabs in display order`() {
        assertThat(DashboardV2NavigationTab.fixedOrder).containsExactly(
            DashboardV2NavigationTab.MAIN,
            DashboardV2NavigationTab.TOOLS,
            DashboardV2NavigationTab.BOLUS,
            DashboardV2NavigationTab.CONFIGURATION,
            DashboardV2NavigationTab.PREFERENCES,
        ).inOrder()
        assertThat(DashboardV2NavigationTab.entries).hasSize(5)
    }

    @Test
    fun `action tabs map to the protected application destinations`() {
        assertThat(DashboardV2NavigationTab.MAIN.elementType).isNull()
        assertThat(DashboardV2NavigationTab.TOOLS.elementType).isNull()
        assertThat(DashboardV2NavigationTab.BOLUS.elementType).isEqualTo(ElementType.INSULIN)
        assertThat(DashboardV2NavigationTab.CONFIGURATION.elementType).isEqualTo(ElementType.CONFIGURATION)
        assertThat(DashboardV2NavigationTab.PREFERENCES.elementType).isEqualTo(ElementType.SETTINGS)
    }
}
