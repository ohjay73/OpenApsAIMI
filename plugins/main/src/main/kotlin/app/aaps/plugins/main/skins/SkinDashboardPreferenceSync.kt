package app.aaps.plugins.main.skins

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps [BooleanKey.OverviewUseDashboardLayout] aligned with the active [SkinInterface]:
 * the "New skin dashboard" ([SkinMinimal]) enables the hybrid dashboard home; other skins disable it.
 */
@Singleton
class SkinDashboardPreferenceSync @Inject constructor(
    private val preferences: Preferences,
    private val skinProvider: SkinProvider,
) {

    /**
     * If user already picked the dashboard skin but the boolean was stuck (e.g. older builds),
     * turn hybrid dashboard on without forcing it off for other combinations at startup.
     */
    fun onStartup() {
        if (skinProvider.activeSkin().prefersDashboardHome &&
            !preferences.get(BooleanKey.OverviewUseDashboardLayout)
        ) {
            preferences.put(BooleanKey.OverviewUseDashboardLayout, true)
        }
    }

    fun onSkinSelectionChanged() {
        val want = skinProvider.activeSkin().prefersDashboardHome
        if (preferences.get(BooleanKey.OverviewUseDashboardLayout) != want) {
            preferences.put(BooleanKey.OverviewUseDashboardLayout, want)
        }
    }
}
