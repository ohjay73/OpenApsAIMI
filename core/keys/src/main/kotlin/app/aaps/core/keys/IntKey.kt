package app.aaps.core.keys

enum class IntKey(
    override val key: Int,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    override val defaultedBySM: Boolean = false,
    override val calculatedDefaultValue: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val engineeringModeOnly: Boolean = false
) : IntPreferenceKey {

    OverviewCarbsButtonIncrement1(R.string.key_carbs_button_increment_1, 5, -50, 50, defaultedBySM = true),
    OverviewCarbsButtonIncrement2(R.string.key_carbs_button_increment_2, 10, -50, 50, defaultedBySM = true),
    OverviewCarbsButtonIncrement3(R.string.key_carbs_button_increment_3, 20, -50, 50, defaultedBySM = true),
    OverviewEatingSoonDuration(R.string.key_eating_soon_duration, 45, 15, 120, defaultedBySM = true, hideParentScreenIfHidden = true),
    OverviewActivityDuration(R.string.key_activity_duration, 90, 15, 600, defaultedBySM = true),
    OverviewHypoDuration(R.string.key_hypo_duration, 60, 15, 180, defaultedBySM = true),
    OverviewCageWarning(R.string.key_statuslights_cage_warning, 48, 24, 240, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewCageCritical(R.string.key_statuslights_cage_critical, 72, 24, 240, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewIageWarning(R.string.key_statuslights_iage_warning, 72, 24, 240, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewIageCritical(R.string.key_statuslights_iage_critical, 144, 24, 240, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewSageWarning(R.string.key_statuslights_sage_warning, 216, 24, 720, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewSageCritical(R.string.key_statuslights_sage_critical, 240, 24, 720, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewSbatWarning(R.string.key_statuslights_sbat_warning, 25, 0, 100, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewSbatCritical(R.string.key_statuslights_sbat_critical, 5, 0, 100, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBageWarning(R.string.key_statuslights_bage_warning, 216, 24, 1000, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBageCritical(R.string.key_statuslights_bage_critical, 240, 24, 1000, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewResWarning(R.string.key_statuslights_res_warning, 80, 0, 300, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewResCritical(R.string.key_statuslights_res_critical, 10, 0, 300, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBattWarning(R.string.key_statuslights_bat_warning, 51, 0, 100, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBattCritical(R.string.key_statuslights_bat_critical, 26, 0, 100, defaultedBySM = true, dependency = BooleanKey.OverviewShowStatusLights),
    OverviewBolusPercentage(R.string.key_boluswizard_percentage, 100, 10, 100),
    OverviewResetBolusPercentageTime(R.string.key_reset_boluswizard_percentage_time, 16, 6, 120, defaultedBySM = true, engineeringModeOnly = true),
    GeneralProtectionTimeout(R.string.key_protection_timeout, 1, 1, 180, defaultedBySM = true),
    SafetyMaxCarbs(R.string.key_safety_max_carbs, 48, 1, 200),
    LoopOpenModeMinChange(R.string.key_loop_open_mode_min_change, 30, 0, 50, defaultedBySM = true),
    ApsMaxSmbFrequency(R.string.key_openaps_smb_interval, 3, 1, 30, defaultedBySM = true, dependency = BooleanKey.ApsUseSmb),
    ApsMaxMinutesOfBasalToLimitSmb(R.string.key_openaps_smb_max_minutes, 30, 15, 120, defaultedBySM = true, dependency = BooleanKey.ApsUseSmb),
    ApsUamMaxMinutesOfBasalToLimitSmb(R.string.key_openaps_uam_smb_max_minutes, 30, 15, 120, defaultedBySM = true, dependency = BooleanKey.ApsUseSmb),
    ApsCarbsRequestThreshold(R.string.key_openaps_carbs_required_threshold, 1, 1, 10, defaultedBySM = true),
    ApsDynIsfAdjustmentFactor(R.string.key_dynamic_isf_adjustment_factor, 100, 1, 300, dependency = BooleanKey.ApsUseDynamicSensitivity),
    AutosensPeriod(R.string.key_openapsama_autosens_period, 24, 4, 24, calculatedDefaultValue = true),
    MaintenanceLogsAmount(R.string.key_maintenance_logs_amount, 2, 1, 10, defaultedBySM = true),
    AlertsStaleDataThreshold(R.string.key_missed_bg_readings_threshold_minutes, 30, 15, 10000, defaultedBySM = true),
    AlertsPumpUnreachableThreshold(R.string.key_pump_unreachable_threshold_minutes, 30, 30, 300, defaultedBySM = true),
    InsulinOrefPeak(R.string.key_insulin_oref_peak, 75, 35, 120, hideParentScreenIfHidden = true),

    AutotuneDefaultTuneDays(R.string.key_autotune_default_tune_days, 5, 1, 30),

    SmsRemoteBolusDistance(R.string.key_smscommunicator_remote_bolus_min_distance, 15, 3, 60),

    BgSourceRandomInterval(R.string.key_randombg_interval_min, 5, 1, 15, defaultedBySM = true),
    GarminLocalHttpPort(R.string.key_garmin_communication_http_port, 28891, 1001, 65535, defaultedBySM = true, hideParentScreenIfHidden = true),
    NsClientAlarmStaleData(R.string.key_ns_alarm_stale_data_value, 16, 15, 120),
    NsClientUrgentAlarmStaleData(R.string.key_ns_alarm_urgent_stale_data_value, 31, 30, 180),


    OApsAIMIDynISFAdjustment(R.string.key_DynISF_Adjust,100,1,500),
    OApsAIMIDynISFAdjustmentHyper(R.string.key_DynISFAdjusthyper,150,1,500),
    OApsAIMImealAdjISFFact(R.string.key_oaps_aimi_mealAdjFact,50,1,500),
    OApsAIMIsleepAdjISFFact(R.string.key_oaps_aimi_sleepAdjFact,50,1,500),
    OApsAIMImealinterval(R.string.key_oaps_aimi_meal_interval, 3, 1, 20, defaultedBySM = true),
    OApsAIMIHCinterval(R.string.key_oaps_aimi_HC_interval, 3, 1, 20, defaultedBySM = true),
    OApsAIMISnackinterval(R.string.key_oaps_aimi_snack_interval, 3, 1, 20, defaultedBySM = true),
    OApsAIMISleepinterval(R.string.key_oaps_aimi_sleep_interval, 3, 1, 20, defaultedBySM = true),
    OApsAIMIHighCarbAdjISFFact(R.string.key_oaps_aimi_highcarbAdjFact,50,1,500),
    OApsAIMISnackAdjISFFact(R.string.key_oaps_aimi_snackAdjFact,50,1,500)
}