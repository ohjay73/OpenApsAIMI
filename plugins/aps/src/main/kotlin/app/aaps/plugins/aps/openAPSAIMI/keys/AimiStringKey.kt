package app.aaps.plugins.aps.openAPSAIMI.keys

import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.plugins.aps.R

enum class AimiStringKey(
    override val key: String,
    override val defaultValue: String,
    override val titleResId: Int,
    override val summaryResId: Int? = null,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val hideParentScreenIfHidden: Boolean = false,
    override val dependency: app.aaps.core.keys.interfaces.BooleanPreferenceKey? = null,
    override val negativeDependency: app.aaps.core.keys.interfaces.BooleanPreferenceKey? = null,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
) : StringPreferenceKey {
    PregnancyDueDateString(
        key = "aimi_pregnancy_due_date_string",
        defaultValue = "",
        titleResId = R.string.OApsAIMI_PregnancyDueDate_title,
        summaryResId = R.string.OApsAIMI_PregnancyDueDate_summary
    ),
    RemoteControlPin(
        key = "aimi_remote_control_pin",
        defaultValue = "",
        titleResId = R.string.OApsAIMI_RemoteControlPin_title,
        summaryResId = R.string.OApsAIMI_RemoteControlPin_summary,
        isPin = true,
        isPassword = true
    ),
}
