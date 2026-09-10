package app.aaps.plugins.main.skins

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION")
class DashboardHomeVariantResolverTest {

    @Test
    fun `fully qualified skin name resolves overview`() {
        val overview = OverviewTestSkin()

        val result = DashboardHomeVariantResolver.resolve(
            storedSkinName = overview.javaClass.name,
            availableSkins = listOf(SkinMinimal(), SkinDashboardV2(), overview),
            fallbackSkin = SkinDashboardV2(),
        )

        assertThat(result).isEqualTo(DashboardHomeVariant.OVERVIEW)
    }

    @Test
    fun `simple skin name resolves dashboard v1`() {
        val dashboardV1 = SkinMinimal()

        val result = DashboardHomeVariantResolver.resolve(
            storedSkinName = dashboardV1.javaClass.simpleName,
            availableSkins = listOf(OverviewTestSkin(), dashboardV1, SkinDashboardV2()),
            fallbackSkin = OverviewTestSkin(),
        )

        assertThat(result).isEqualTo(DashboardHomeVariant.DASHBOARD_V1)
    }

    @Test
    fun `dashboard v2 skin advertises and resolves dashboard v2`() {
        val dashboardV2 = SkinDashboardV2()

        val result = DashboardHomeVariantResolver.resolve(
            storedSkinName = dashboardV2.javaClass.name,
            availableSkins = listOf(OverviewTestSkin(), SkinMinimal(), dashboardV2),
            fallbackSkin = OverviewTestSkin(),
        )

        assertThat(dashboardV2.dashboardHomeVariant).isEqualTo(DashboardHomeVariant.DASHBOARD_V2)
        assertThat(result).isEqualTo(DashboardHomeVariant.DASHBOARD_V2)
    }

    @Test
    fun `unknown skin uses active skin fallback`() {
        val fallback = SkinDashboardV2()

        val result = DashboardHomeVariantResolver.resolve(
            storedSkinName = "missing.skin.Name",
            availableSkins = listOf(OverviewTestSkin(), SkinMinimal()),
            fallbackSkin = fallback,
        )

        assertThat(result).isEqualTo(DashboardHomeVariant.DASHBOARD_V2)
    }

    private class OverviewTestSkin : SkinInterface {
        override val description: Int = 0
        override val mainGraphHeight: Int = 0
        override val secondaryGraphHeight: Int = 0
    }
}
