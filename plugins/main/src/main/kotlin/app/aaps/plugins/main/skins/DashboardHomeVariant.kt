package app.aaps.plugins.main.skins

/** Selects the home renderer without coupling the Compose shell to a concrete skin class. */
enum class DashboardHomeVariant {
    OVERVIEW,
    DASHBOARD_V1,
    DASHBOARD_V2,
    GLASS,
}
