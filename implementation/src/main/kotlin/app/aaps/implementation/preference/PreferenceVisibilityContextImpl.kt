package app.aaps.implementation.preference

import app.aaps.core.data.model.advancedFilteringSupported
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.keys.interfaces.PreferenceVisibilityContext
import app.aaps.core.keys.interfaces.Preferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [PreferenceVisibilityContext] that provides runtime context
 * for evaluating preference visibility conditions.
 *
 * This class bridges the gap between the preference key definitions (which declare
 * visibility conditions) and the runtime state of the app (pump type, BG source, etc.).
 */
@Singleton
class PreferenceVisibilityContextImpl @Inject constructor(
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    override val preferences: Preferences
) : PreferenceVisibilityContext {

    override val isPatchPump: Boolean
        get() = activePlugin.activePump.pumpDescription.isPatchPump

    override val isBatteryReplaceable: Boolean
        get() = activePlugin.activePump.pumpDescription.isBatteryReplaceable

    override val isBatteryChangeLoggingEnabled: Boolean
        get() = activePlugin.activePump.isBatteryChangeLoggingEnabled()

    override val advancedFilteringSupported: Boolean
        get() = iobCobCalculator.ads.lastBg()?.sourceSensor?.advancedFilteringSupported() ?: false

    override val isPumpInitialized: Boolean
        get() = activePlugin.activePump.isInitialized()
}
