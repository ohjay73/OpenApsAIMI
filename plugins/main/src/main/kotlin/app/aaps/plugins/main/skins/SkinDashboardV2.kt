package app.aaps.plugins.main.skins

import app.aaps.plugins.main.R
import javax.inject.Inject

/** Opt-in skin for the Tarciso-inspired Compose dashboard. */
class SkinDashboardV2 @Inject constructor() : SkinInterface {

    override val description: Int = R.string.dashboard_v2_skin_description
    override val mainGraphHeight: Int = 180
    override val secondaryGraphHeight: Int = 0
    override val dashboardHomeVariant: DashboardHomeVariant get() = DashboardHomeVariant.DASHBOARD_V2
}
