package app.aaps.plugins.main.general.dashboard.viewmodel

/** Pure numeric selection rules for values displayed by DASHBOARD_V2. */
internal object DashboardV2StatusValueResolver {

    data class TargetRangeMgdl(
        val low: Double,
        val high: Double,
    )

    data class TemporaryBasal(
        val rate: Double,
        val isAbsolute: Boolean,
    )

    fun resolveTargetRangeMgdl(
        temporaryLow: Double?,
        temporaryHigh: Double?,
        profileLow: Double?,
        profileHigh: Double?,
    ): TargetRangeMgdl? =
        completeTargetRange(temporaryLow, temporaryHigh)
            ?: completeTargetRange(profileLow, profileHigh)

    fun resolveEffectiveBasalRateUh(
        temporaryBasal: TemporaryBasal?,
        scheduledBasalRateUh: Double?,
    ): Double? {
        if (temporaryBasal == null) return scheduledBasalRateUh.finiteOrNull()
        if (temporaryBasal.isAbsolute) return temporaryBasal.rate.finiteOrNull()
        val scheduled = scheduledBasalRateUh.finiteOrNull() ?: return null
        return (scheduled * temporaryBasal.rate / 100.0).finiteOrNull()
    }

    private fun completeTargetRange(low: Double?, high: Double?): TargetRangeMgdl? {
        val completeLow = low.finiteOrNull() ?: return null
        val completeHigh = high.finiteOrNull() ?: return null
        return TargetRangeMgdl(low = completeLow, high = completeHigh)
    }

    private fun Double?.finiteOrNull(): Double? = this?.takeIf(Double::isFinite)
}
