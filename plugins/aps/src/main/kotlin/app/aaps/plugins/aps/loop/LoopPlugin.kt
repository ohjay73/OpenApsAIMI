package app.aaps.plugins.aps.loop

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.aps.ApsMode
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.DS
import app.aaps.core.data.model.OE
import app.aaps.core.data.model.TE
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.Loop.LastRun
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.objects.Instantiator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAcceptOpenLoopChange
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.interfaces.rx.events.EventNewOpenLoopNotification
import app.aaps.core.interfaces.rx.events.EventTempTargetChange
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.core.nssdk.interfaces.RunningConfiguration
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.asAnnouncement
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.convertedToPercent
import app.aaps.core.objects.extensions.json
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveListPreference
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.loop.events.EventLoopSetLastRunGui
import app.aaps.plugins.aps.loop.extensions.json
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class LoopPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    private val aapsSchedulers: AapsSchedulers,
    private val rxBus: RxBus,
    private val sp: SP,
    private val preferences: Preferences,
    private val config: Config,
    private val constraintChecker: ConstraintsChecker,
    rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val context: Context,
    private val commandQueue: CommandQueue,
    private val activePlugin: ActivePlugin,
    private val virtualPump: VirtualPump,
    private val iobCobCalculator: IobCobCalculator,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val receiverStatusStore: ReceiverStatusStore,
    private val fabricPrivacy: FabricPrivacy,
    private val dateUtil: DateUtil,
    private val uel: UserEntryLogger,
    private val persistenceLayer: PersistenceLayer,
    private val runningConfiguration: RunningConfiguration,
    private val uiInteraction: UiInteraction,
    private val instantiator: Instantiator,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val glucoseStatusProvider: GlucoseStatusProvider
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.LOOP)
        .fragmentClass(LoopFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_loop_closed_white)
        .pluginName(app.aaps.core.ui.R.string.loop)
        .shortName(R.string.loop_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .enableByDefault(config.APS)
        .description(R.string.description_loop),
    aapsLogger, rh
), Loop {

    private val disposable = CompositeDisposable()
    override var lastBgTriggeredRun: Long = 0
    private var carbsSuggestionsSuspendedUntil: Long = 0
    private var prevCarbsreq = 0
    override var lastRun: LastRun? = null
    override var closedLoopEnabled: Constraint<Boolean>? = null

    private var handler: Handler? = null

    override fun onStart() {
        createNotificationChannel()
        super.onStart()
        handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)
        disposable += rxBus
            .toObservable(EventTempTargetChange::class.java)
            .observeOn(aapsSchedulers.io)
            // Skip db change of ending previous TT
            .debounce(10L, TimeUnit.SECONDS)
            .subscribe({ invoke("EventTempTargetChange", true) }, fabricPrivacy::logException)
        // Démarrage du déclenchement périodique
        startPeriodicLoop()
    }
    /*private fun startPeriodicLoop() {
        // On récupère la valeur ApsMaxSmbFrequency en minutes
        val freqMinutes = preferences.get(IntKey.ApsMaxSmbFrequency).toLong()
        // On convertit en millisecondes
        val freqMs = T.mins(freqMinutes).msecs()

        // Définition du Runnable qui relancera votre loop, puis se replanifiera
        val periodicRunnable = object : Runnable {
            override fun run() {
                // On relance le loop
                invoke("PeriodicApsMaxSmbFrequency", true)

                // On reprogramme le prochain run dans freqMs
                handler?.postDelayed(this, freqMs)
            }
        }

        // On lance la première fois maintenant
        handler?.postDelayed(periodicRunnable, freqMs)
    }*/
    private fun startPeriodicLoop() {
    // Récupère l'intervalle (en minutes) pour la planification
    val freqMinutes = preferences.get(IntKey.ApsMaxSmbFrequency).toLong()
    val freqMs = T.mins(freqMinutes).msecs()

    val periodicRunnable = object : Runnable {
        override fun run() {
            // 1) Vérifie l'option autodrive
            val autodrive = preferences.get(BooleanKey.OApsAIMIautoDrive)

            // 2) Récupère la glycémie (ajustez le code si la variable/méthode diffère)
            val currentBG = glucoseStatusProvider.glucoseStatusData?.glucose

            // 3) Condition : autodrive activé ET glycémie disponible >= 120
            if (autodrive && currentBG != null && currentBG >= 130.0) {
                aapsLogger.debug(LTag.APS, "OApsAIMIautoDrive=$autodrive; BG=$currentBG => on lance le loop.")
                invoke("PeriodicApsMaxSmbFrequency", true)
            } else {
                // Sinon, on logge qu'on ne fait rien
                aapsLogger.debug(LTag.APS, "Pas de loop : autodrive=$autodrive; BG=$currentBG (<130 ?).")
            }

            // Replanifie le prochain cycle
            handler?.postDelayed(this, freqMs)
        }
    }

    // Lance la première exécution
    handler?.postDelayed(periodicRunnable, freqMs)
}
    private fun createNotificationChannel() {
        val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        @SuppressLint("WrongConstant") val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_ID,
            NotificationManager.IMPORTANCE_HIGH
        )
        mNotificationManager.createNotificationChannel(channel)
    }

    override fun onStop() {
        disposable.clear()
        handler?.removeCallbacksAndMessages(null)
        handler = null
        super.onStop()
    }

    override fun specialEnableCondition(): Boolean {
        return try {
            val pump = activePlugin.activePump
            pump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            // may fail during initialization
            true
        }
    }

    override fun minutesToEndOfSuspend(): Int {
        val offlineEvent = persistenceLayer.getOfflineEventActiveAt(dateUtil.now())
        return if (offlineEvent != null) T.msecs(offlineEvent.timestamp + offlineEvent.duration - dateUtil.now()).mins().toInt()
        else 0
    }

    override val isSuspended: Boolean
        get() = persistenceLayer.getOfflineEventActiveAt(dateUtil.now()) != null

    override val isLGS: Boolean
        get() {
            val closedLoopEnabled = constraintChecker.isClosedLoopAllowed()
            val maxIobAllowed = constraintChecker.getMaxIOBAllowed().value()
            val apsMode = ApsMode.fromString(preferences.get(StringKey.LoopApsMode))
            val pump = activePlugin.activePump
            var isLGS = false
            if (!isSuspended && !pump.isSuspended()) if (closedLoopEnabled.value()) if (maxIobAllowed == HardLimits.MAX_IOB_LGS || apsMode == ApsMode.LGS) isLGS = true
            return isLGS
        }

    override val isSuperBolus: Boolean
        get() {
            val offlineEvent = persistenceLayer.getOfflineEventActiveAt(dateUtil.now())
            return offlineEvent?.reason == OE.Reason.SUPER_BOLUS
        }

    override val isDisconnected: Boolean
        get() {
            val offlineEvent = persistenceLayer.getOfflineEventActiveAt(dateUtil.now())
            return offlineEvent?.reason == OE.Reason.DISCONNECT_PUMP
        }

    @Suppress("SameParameterValue")
    private fun treatmentTimeThreshold(durationMinutes: Int): Boolean {
        val threshold = System.currentTimeMillis() + durationMinutes * 60 * 1000
        var bool = false
        val lastBolusTime = persistenceLayer.getNewestBolus()?.timestamp ?: 0L
        val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
        if (lastBolusTime > threshold || lastCarbsTime > threshold) bool = true
        return bool
    }

    @Synchronized
    fun isEmptyQueue(): Boolean {
        val maxMinutes = 2L
        val start = dateUtil.now()
        while (start + T.mins(maxMinutes).msecs() > dateUtil.now()) {
            if (commandQueue.size() == 0 && commandQueue.performing() == null) return true
            SystemClock.sleep(1000)
        }
        return false
    }

    @Synchronized
    override fun invoke(initiator: String, allowNotification: Boolean, tempBasalFallback: Boolean) {
        try {
            aapsLogger.debug(LTag.APS, "invoke from $initiator")
            val loopEnabled = constraintChecker.isLoopInvocationAllowed()
            if (!loopEnabled.value()) {
                val message = """
                    ${rh.gs(app.aaps.core.ui.R.string.loop_disabled)}
                    ${loopEnabled.getReasons()}
                    """.trimIndent()
                aapsLogger.debug(LTag.APS, message)
                rxBus.send(EventLoopSetLastRunGui(message))
                return
            }
            val pump = activePlugin.activePump
            var apsResult: APSResult? = null
            if (!isEnabled()) return
            val profile = profileFunction.getProfile()
            if (profile == null || !profileFunction.isProfileValid("Loop")) {
                aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
                rxBus.send(EventLoopSetLastRunGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
                return
            }

            if (!isEmptyQueue()) {
                aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.pump_busy))
                rxBus.send(EventLoopSetLastRunGui(rh.gs(app.aaps.core.ui.R.string.pump_busy)))
                return
            }

            // Check if pump info is loaded
            if (pump.baseBasalRate < 0.01) return
            val usedAPS = activePlugin.activeAPS
            if (usedAPS.isEnabled()) {
                usedAPS.invoke(initiator, tempBasalFallback)
                apsResult = usedAPS.lastAPSResult
            }

            // Check if we have any result
            if (apsResult == null) {
                rxBus.send(EventLoopSetLastRunGui(rh.gs(R.string.no_aps_selected)))
                return
            }

            // Store calculations to DB
            disposable += persistenceLayer.insertOrUpdateApsResult(apsResult).subscribe()

            // Prepare for pumps using % basals
            if (pump.pumpDescription.tempBasalStyle == PumpDescription.PERCENT && allowPercentage()) {
                apsResult.usePercent = true
            }
            apsResult.percent = (apsResult.rate / profile.getBasal() * 100).toInt()

            // check rate for constraints
            val resultAfterConstraints = apsResult.newAndClone()
            resultAfterConstraints.rateConstraint = ConstraintObject(resultAfterConstraints.rate, aapsLogger)
            resultAfterConstraints.rate = constraintChecker.applyBasalConstraints(resultAfterConstraints.rateConstraint!!, profile).value()
            resultAfterConstraints.percentConstraint = ConstraintObject(resultAfterConstraints.percent, aapsLogger)
            resultAfterConstraints.percent = constraintChecker.applyBasalPercentConstraints(resultAfterConstraints.percentConstraint!!, profile).value()
            resultAfterConstraints.smbConstraint = ConstraintObject(resultAfterConstraints.smb, aapsLogger)
            resultAfterConstraints.smb = constraintChecker.applyBolusConstraints(resultAfterConstraints.smbConstraint!!).value()

            // safety check for multiple SMBs
            val lastBolusTime = persistenceLayer.getNewestBolus()?.timestamp ?: 0L
            if (lastBolusTime != 0L && lastBolusTime + T.mins(preferences.get(IntKey.ApsMaxSmbFrequency).toLong()).msecs() > dateUtil.now()) {
                aapsLogger.debug(LTag.APS, "SMB requested but still in ${preferences.get(IntKey.ApsMaxSmbFrequency)} min interval")
                resultAfterConstraints.smb = 0.0
            }
            prevCarbsreq = lastRun?.constraintsProcessed?.carbsReq ?: prevCarbsreq
            if (lastRun == null) lastRun = LastRun()
            lastRun?.let { lastRun ->
                lastRun.request = apsResult
                lastRun.constraintsProcessed = resultAfterConstraints
                lastRun.lastAPSRun = dateUtil.now()
                lastRun.source = (usedAPS as PluginBase).name
                lastRun.tbrSetByPump = null
                lastRun.smbSetByPump = null
                lastRun.lastTBREnact = 0
                lastRun.lastTBRRequest = 0
                lastRun.lastSMBEnact = 0
                lastRun.lastSMBRequest = 0
                scheduleBuildAndStoreDeviceStatus("APS result")

                if (isSuspended) {
                    aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.loopsuspended))
                    rxBus.send(EventLoopSetLastRunGui(rh.gs(app.aaps.core.ui.R.string.loopsuspended)))
                    return
                }
                if (pump.isSuspended()) {
                    aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.pumpsuspended))
                    rxBus.send(EventLoopSetLastRunGui(rh.gs(app.aaps.core.ui.R.string.pumpsuspended)))
                    return
                }
                closedLoopEnabled = constraintChecker.isClosedLoopAllowed()
                if (closedLoopEnabled?.value() == true) {
                    if (allowNotification) {
                        if (resultAfterConstraints.isCarbsRequired
                            && resultAfterConstraints.carbsReq >= sp.getInt(
                                R.string.key_smb_enable_carbs_suggestions_threshold,
                                0
                            ) && carbsSuggestionsSuspendedUntil < System.currentTimeMillis() && !treatmentTimeThreshold(-15)
                        ) {
                            if (preferences.get(BooleanKey.AlertCarbsRequired) && !sp.getBoolean(app.aaps.core.ui.R.string.key_raise_notifications_as_android_notifications, true)
                            ) {
                                val carbReqLocal = Notification(Notification.CARBS_REQUIRED, resultAfterConstraints.carbsRequiredText, Notification.NORMAL)
                                rxBus.send(EventNewNotification(carbReqLocal))
                            }
                            if (preferences.get(BooleanKey.NsClientCreateAnnouncementsFromCarbsReq) && config.APS) {
                                disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                                    therapyEvent = TE.asAnnouncement(resultAfterConstraints.carbsRequiredText),
                                    timestamp = dateUtil.now(),
                                    action = Action.TREATMENT,
                                    source = Sources.Loop,
                                    note = resultAfterConstraints.carbsRequiredText,
                                    listValues = listOf()
                                ).subscribe()
                            }
                            if (preferences.get(BooleanKey.AlertCarbsRequired) && sp.getBoolean(app.aaps.core.ui.R.string.key_raise_notifications_as_android_notifications, true)
                            ) {
                                val intentAction5m = Intent(context, CarbSuggestionReceiver::class.java)
                                intentAction5m.putExtra("ignoreDuration", 5)
                                val pendingIntent5m = PendingIntent.getBroadcast(context, 1, intentAction5m, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                                val actionIgnore5m = NotificationCompat.Action(app.aaps.core.objects.R.drawable.ic_notif_aaps, rh.gs(R.string.ignore5m, "Ignore 5m"), pendingIntent5m)
                                val intentAction15m = Intent(context, CarbSuggestionReceiver::class.java)
                                intentAction15m.putExtra("ignoreDuration", 15)
                                val pendingIntent15m = PendingIntent.getBroadcast(context, 1, intentAction15m, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                                val actionIgnore15m = NotificationCompat.Action(app.aaps.core.objects.R.drawable.ic_notif_aaps, rh.gs(R.string.ignore15m, "Ignore 15m"), pendingIntent15m)
                                val intentAction30m = Intent(context, CarbSuggestionReceiver::class.java)
                                intentAction30m.putExtra("ignoreDuration", 30)
                                val pendingIntent30m = PendingIntent.getBroadcast(context, 1, intentAction30m, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                                val actionIgnore30m = NotificationCompat.Action(app.aaps.core.objects.R.drawable.ic_notif_aaps, rh.gs(R.string.ignore30m, "Ignore 30m"), pendingIntent30m)
                                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                                builder.setSmallIcon(app.aaps.core.ui.R.drawable.notif_icon)
                                    .setContentTitle(rh.gs(R.string.carbs_suggestion))
                                    .setContentText(resultAfterConstraints.carbsRequiredText)
                                    .setAutoCancel(true)
                                    .setPriority(Notification.IMPORTANCE_HIGH)
                                    .setCategory(Notification.CATEGORY_ALARM)
                                    .addAction(actionIgnore5m)
                                    .addAction(actionIgnore15m)
                                    .addAction(actionIgnore30m)
                                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                                    .setVibrate(longArrayOf(1000, 1000, 1000, 1000, 1000))
                                val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                                // mId allows you to update the notification later on.
                                mNotificationManager.notify(Constants.notificationID, builder.build())
                                uel.log(
                                    action = Action.CAREPORTAL,
                                    source = Sources.Loop,
                                    note = rh.gs(app.aaps.core.ui.R.string.carbsreq, resultAfterConstraints.carbsReq, resultAfterConstraints.carbsReqWithin),
                                    listValues = listOf(
                                        ValueWithUnit.Gram(resultAfterConstraints.carbsReq),
                                        ValueWithUnit.Minute(resultAfterConstraints.carbsReqWithin)
                                    )
                                )
                                rxBus.send(EventNewOpenLoopNotification())

                                //only send to wear if Native notifications are turned off
                                if (!sp.getBoolean(app.aaps.core.ui.R.string.key_raise_notifications_as_android_notifications, true)) {
                                    // Send to Wear
                                    sendToWear(resultAfterConstraints.carbsRequiredText)
                                }
                            }
                        } else {
                            //If carbs were required previously, but are no longer needed, dismiss notifications
                            if (prevCarbsreq > 0) {
                                dismissSuggestion()
                                rxBus.send(EventDismissNotification(Notification.CARBS_REQUIRED))
                            }
                        }
                    }
                    if (resultAfterConstraints.isChangeRequested
                        && !commandQueue.bolusInQueue()
                    ) {
                        val waiting = instantiator.providePumpEnactResult()
                        waiting.queued = true
                        if (resultAfterConstraints.isTempBasalRequested) lastRun.tbrSetByPump = waiting
                        if (resultAfterConstraints.isBolusRequested) lastRun.smbSetByPump =
                            waiting
                        rxBus.send(EventLoopUpdateGui())
                        fabricPrivacy.logCustom("APSRequest")
                        // TBR request must be applied first to prevent situation where
                        // SMB was executed and zero TBR afterwards failed
                        applyTBRRequest(resultAfterConstraints, profile, object : Callback() {
                            override fun run() {
                                if (result.enacted || result.success) {
                                    lastRun.tbrSetByPump = result
                                    lastRun.lastTBRRequest = lastRun.lastAPSRun
                                    lastRun.lastTBREnact = dateUtil.now()
                                    // deliverAt is used to prevent executing too old SMB request (older than 1 min)
                                    // executing TBR may take some time thus give more time to SMB
                                    resultAfterConstraints.deliverAt = lastRun.lastTBREnact
                                    rxBus.send(EventLoopUpdateGui())
                                    if (resultAfterConstraints.isBolusRequested)
                                        applySMBRequest(resultAfterConstraints, object : Callback() {
                                            override fun run() {
                                                // Callback is only called if a bolus was actually requested
                                                if (result.enacted || result.success) {
                                                    lastRun.smbSetByPump = result
                                                    lastRun.lastSMBRequest = lastRun.lastAPSRun
                                                    lastRun.lastSMBEnact = dateUtil.now()
                                                    scheduleBuildAndStoreDeviceStatus("applySMBRequest")
                                                } else {
                                                    handler?.postDelayed({ invoke("tempBasalFallback", allowNotification, true) }, 1000)
                                                }
                                                rxBus.send(EventLoopUpdateGui())
                                            }
                                        })
                                    else {
                                        aapsLogger.debug(LTag.APS, "No SMB requested")
                                        scheduleBuildAndStoreDeviceStatus("applyTBRRequest")
                                    }
                                } else {
                                    lastRun.tbrSetByPump = result
                                    lastRun.lastTBRRequest = lastRun.lastAPSRun
                                }
                                rxBus.send(EventLoopUpdateGui())
                            }
                        })
                    } else {
                        lastRun.tbrSetByPump = null
                        lastRun.smbSetByPump = null
                    }
                } else {
                    if (resultAfterConstraints.isChangeRequested && allowNotification) {
                        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                        builder.setSmallIcon(app.aaps.core.ui.R.drawable.notif_icon)
                            .setContentTitle(rh.gs(R.string.open_loop_new_suggestion))
                            .setContentText(resultAfterConstraints.resultAsString())
                            .setAutoCancel(true)
                            .setPriority(Notification.IMPORTANCE_HIGH)
                            .setCategory(Notification.CATEGORY_ALARM)
                            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        if (preferences.get(BooleanKey.WearControl)) {
                            builder.setLocalOnly(true)
                        }
                        presentSuggestion(builder, resultAfterConstraints.resultAsString())
                    } else if (allowNotification) {
                        dismissSuggestion()
                    }
                }
                rxBus.send(EventLoopUpdateGui())
            }
        } finally {
            aapsLogger.debug(LTag.APS, "invoke end")
        }
    }

    override fun disableCarbSuggestions(durationMinutes: Int) {
        carbsSuggestionsSuspendedUntil = System.currentTimeMillis() + durationMinutes * 60 * 1000
        dismissSuggestion()
    }

    private fun presentSuggestion(builder: NotificationCompat.Builder, contentText: String) {
        // Creates an explicit intent for an Activity in your app
        val resultIntent = Intent(context, uiInteraction.mainActivity)

        // The stack builder object will contain an artificial back stack for the
        // started Activity.
        // This ensures that navigating backward from the Activity leads out of
        // your application to the Home screen.
        val stackBuilder = TaskStackBuilder.create(context)
        stackBuilder.addParentStack(uiInteraction.mainActivity)
        // Adds the Intent that starts the Activity to the top of the stack
        stackBuilder.addNextIntent(resultIntent)
        val resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        builder.setContentIntent(resultPendingIntent)
        builder.setVibrate(longArrayOf(1000, 1000, 1000, 1000, 1000))
        val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // mId allows you to update the notification later on.
        mNotificationManager.notify(Constants.notificationID, builder.build())
        rxBus.send(EventNewOpenLoopNotification())

        // Send to Wear
        sendToWear(contentText)
    }

    private fun dismissSuggestion() {
        // dismiss notifications
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(Constants.notificationID)
        rxBus.send(EventMobileToWear(EventData.CancelNotification(dateUtil.now())))
    }

    private fun sendToWear(contentText: String) {
        lastRun?.let {
            rxBus.send(
                EventMobileToWear(
                    EventData.OpenLoopRequest(
                        rh.gs(R.string.open_loop_new_suggestion),
                        contentText,
                        EventData.OpenLoopRequestConfirmed(dateUtil.now())
                    )
                )
            )
        }
    }

    override fun acceptChangeRequest() {
        val profile = profileFunction.getProfile() ?: return
        lastRun?.let { lastRun ->
            lastRun.constraintsProcessed?.let { constraintsProcessed ->
                applyTBRRequest(constraintsProcessed, profile, object : Callback() {
                    override fun run() {
                        if (result.enacted) {
                            lastRun.tbrSetByPump = result
                            lastRun.lastTBRRequest = lastRun.lastAPSRun
                            lastRun.lastTBREnact = dateUtil.now()
                            lastRun.lastOpenModeAccept = dateUtil.now()
                            scheduleBuildAndStoreDeviceStatus("acceptChangeRequest")
                            sp.incInt(app.aaps.core.utils.R.string.key_ObjectivesmanualEnacts)
                        }
                        rxBus.send(EventAcceptOpenLoopChange())
                    }
                })
            }
        }
        fabricPrivacy.logCustom("AcceptTemp")
    }

    /**
     * expect absolute request and allow both absolute and percent response based on pump capabilities
     * TODO: update pump drivers to support APS request in %
     */
    private fun applyTBRRequest(request: APSResult, profile: Profile, callback: Callback?) {
        if (!request.isTempBasalRequested) {
            callback?.result(instantiator.providePumpEnactResult().enacted(false).success(true).comment(app.aaps.core.ui.R.string.nochangerequested))?.run()
            return
        }
        val pump = activePlugin.activePump
        if (!pump.isInitialized()) {
            aapsLogger.debug(LTag.APS, "applyAPSRequest: " + rh.gs(R.string.pump_not_initialized))
            callback?.result(instantiator.providePumpEnactResult().comment(R.string.pump_not_initialized).enacted(false).success(false))?.run()
            return
        }
        if (pump.isSuspended()) {
            aapsLogger.debug(LTag.APS, "applyAPSRequest: " + rh.gs(app.aaps.core.ui.R.string.pumpsuspended))
            callback?.result(instantiator.providePumpEnactResult().comment(app.aaps.core.ui.R.string.pumpsuspended).enacted(false).success(false))?.run()
            return
        }
        aapsLogger.debug(LTag.APS, "applyAPSRequest: $request")
        val now = System.currentTimeMillis()
        val activeTemp = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        if (request.rate == 0.0 && request.duration == 0 || abs(request.rate - pump.baseBasalRate) < pump.pumpDescription.basalStep) {
            if (activeTemp != null) {
                aapsLogger.debug(LTag.APS, "applyAPSRequest: cancelTempBasal()")
                uel.log(Action.CANCEL_TEMP_BASAL, Sources.Loop)
                commandQueue.cancelTempBasal(false, callback)
            } else {
                aapsLogger.debug(LTag.APS, "applyAPSRequest: Basal set correctly")
                callback?.result(
                    instantiator.providePumpEnactResult().absolute(request.rate).duration(0)
                        .enacted(false).success(true).comment(R.string.basal_set_correctly)
                )?.run()
            }
        } else if (request.usePercent && allowPercentage()) {
            if (request.percent == 100 && request.duration == 0) {
                if (activeTemp != null) {
                    aapsLogger.debug(LTag.APS, "applyAPSRequest: cancelTempBasal()")
                    uel.log(Action.CANCEL_TEMP_BASAL, Sources.Loop)
                    commandQueue.cancelTempBasal(false, callback)
                } else {
                    aapsLogger.debug(LTag.APS, "applyAPSRequest: Basal set correctly")
                    callback?.result(
                        instantiator.providePumpEnactResult().percent(request.percent).duration(0)
                            .enacted(false).success(true).comment(R.string.basal_set_correctly)
                    )?.run()
                }
            } else if (activeTemp != null && activeTemp.plannedRemainingMinutes > 5 && request.duration - activeTemp.plannedRemainingMinutes < 30 && request.percent == activeTemp.convertedToPercent(
                    now,
                    profile
                )
            ) {
                aapsLogger.debug(LTag.APS, "applyAPSRequest: Temp basal set correctly")
                callback?.result(
                    instantiator.providePumpEnactResult().percent(request.percent)
                        .enacted(false).success(true).duration(activeTemp.plannedRemainingMinutes)
                        .comment(app.aaps.core.ui.R.string.let_temp_basal_run)
                )?.run()
            } else {
                aapsLogger.debug(LTag.APS, "applyAPSRequest: tempBasalPercent()")
                uel.log(
                    action = Action.TEMP_BASAL,
                    source = Sources.Loop,
                    listValues = listOf(
                        ValueWithUnit.Percent(request.percent),
                        ValueWithUnit.Minute(request.duration)
                    )
                )
                commandQueue.tempBasalPercent(request.percent, request.duration, false, profile, PumpSync.TemporaryBasalType.NORMAL, callback)
            }
        } else {
            if (activeTemp != null && activeTemp.plannedRemainingMinutes > 5 && request.duration - activeTemp.plannedRemainingMinutes < 30 && abs(
                    request.rate - activeTemp.convertedToAbsolute(
                        now,
                        profile
                    )
                ) < pump.pumpDescription.basalStep
            ) {
                aapsLogger.debug(LTag.APS, "applyAPSRequest: Temp basal set correctly")
                callback?.result(
                    instantiator.providePumpEnactResult().absolute(activeTemp.convertedToAbsolute(now, profile))
                        .enacted(false).success(true).duration(activeTemp.plannedRemainingMinutes)
                        .comment(app.aaps.core.ui.R.string.let_temp_basal_run)
                )?.run()
            } else {
                aapsLogger.debug(LTag.APS, "applyAPSRequest: setTempBasalAbsolute()")
                uel.log(
                    action = Action.TEMP_BASAL,
                    source = Sources.Loop,
                    listValues = listOf(
                        ValueWithUnit.UnitPerHour(request.rate),
                        ValueWithUnit.Minute(request.duration)
                    )
                )
                commandQueue.tempBasalAbsolute(request.rate, request.duration, false, profile, PumpSync.TemporaryBasalType.NORMAL, callback)
            }
        }
    }

    private fun applySMBRequest(request: APSResult, callback: Callback?) {
        val pump = activePlugin.activePump
        val lastBolusTime = persistenceLayer.getNewestBolus()?.timestamp ?: 0L
        if (lastBolusTime != 0L && lastBolusTime + T.mins(preferences.get(IntKey.ApsMaxSmbFrequency).toLong()).msecs() > dateUtil.now()) {
            aapsLogger.debug(LTag.APS, "SMB requested but still in ${preferences.get(IntKey.ApsMaxSmbFrequency)} min interval")
            callback?.result(
                instantiator.providePumpEnactResult()
                    .comment(R.string.smb_frequency_exceeded)
                    .enacted(false).success(false)
            )?.run()
            return
        }
        if (!pump.isInitialized()) {
            aapsLogger.debug(LTag.APS, "applySMBRequest: " + rh.gs(R.string.pump_not_initialized))
            callback?.result(instantiator.providePumpEnactResult().comment(R.string.pump_not_initialized).enacted(false).success(false))?.run()
            return
        }
        if (pump.isSuspended()) {
            aapsLogger.debug(LTag.APS, "applySMBRequest: " + rh.gs(app.aaps.core.ui.R.string.pumpsuspended))
            callback?.result(instantiator.providePumpEnactResult().comment(app.aaps.core.ui.R.string.pumpsuspended).enacted(false).success(false))?.run()
            return
        }
        aapsLogger.debug(LTag.APS, "applySMBRequest: $request")

        // deliver SMB
        val detailedBolusInfo = DetailedBolusInfo()
        detailedBolusInfo.lastKnownBolusTime = persistenceLayer.getNewestBolus()?.timestamp ?: 0L
        detailedBolusInfo.eventType = TE.Type.CORRECTION_BOLUS
        detailedBolusInfo.insulin = request.smb
        detailedBolusInfo.bolusType = BS.Type.SMB
        detailedBolusInfo.deliverAtTheLatest = request.deliverAt
        aapsLogger.debug(LTag.APS, "applyAPSRequest: bolus()")
        if (request.smb > 0.0)
            uel.log(action = Action.SMB, source = Sources.Loop, value = ValueWithUnit.Insulin(detailedBolusInfo.insulin))
        commandQueue.bolus(detailedBolusInfo, callback)
    }

    private fun allowPercentage(): Boolean {
        return virtualPump.isEnabled()
    }

    override fun goToZeroTemp(durationInMinutes: Int, profile: Profile, reason: OE.Reason, action: Action, source: Sources, listValues: List<ValueWithUnit>) {
        val pump = activePlugin.activePump
        disposable += persistenceLayer.insertAndCancelCurrentOfflineEvent(
            offlineEvent = OE(
                timestamp = dateUtil.now(),
                duration = T.mins(durationInMinutes.toLong()).msecs(),
                reason = reason
            ),
            action = action,
            source = source,
            note = null,
            listValues = listValues
        ).subscribe()
        if (pump.pumpDescription.tempBasalStyle == PumpDescription.ABSOLUTE) {
            commandQueue.tempBasalAbsolute(0.0, durationInMinutes, true, profile, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND, object : Callback() {
                override fun run() {
                    if (!result.success) {
                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.temp_basal_delivery_error), app.aaps.core.ui.R.raw.boluserror)
                    }
                }
            })
        } else {
            commandQueue.tempBasalPercent(0, durationInMinutes, true, profile, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND, object : Callback() {
                override fun run() {
                    if (!result.success) {
                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.temp_basal_delivery_error), app.aaps.core.ui.R.raw.boluserror)
                    }
                }
            })
        }
        if (pump.pumpDescription.isExtendedBolusCapable && persistenceLayer.getExtendedBolusActiveAt(dateUtil.now()) != null) {
            commandQueue.cancelExtended(object : Callback() {
                override fun run() {
                    if (!result.success) {
                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.extendedbolusdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                    }
                }
            })
        }
    }

    override fun suspendLoop(durationInMinutes: Int, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>) {
        disposable += persistenceLayer.insertAndCancelCurrentOfflineEvent(
            offlineEvent = OE(timestamp = dateUtil.now(), duration = T.mins(durationInMinutes.toLong()).msecs(), reason = OE.Reason.SUSPEND),
            action = action,
            source = source,
            note = note,
            listValues = listValues
        ).subscribe()
        commandQueue.cancelTempBasal(true, object : Callback() {
            override fun run() {
                if (!result.success) {
                    uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.temp_basal_delivery_error), app.aaps.core.ui.R.raw.boluserror)
                }
            }
        })
    }

    var task: Runnable? = null

    override fun scheduleBuildAndStoreDeviceStatus(reason: String) {
        class UpdateRunnable : Runnable {

            override fun run() {
                buildAndStoreDeviceStatus(reason)
                task = null
            }
        }
        task?.let { handler?.removeCallbacks(it) }
        task = UpdateRunnable()
        task?.let { handler?.postDelayed(it, 5000) }
    }

    fun buildAndStoreDeviceStatus(reason: String) {
        aapsLogger.debug(LTag.NSCLIENT, "Building DeviceStatus for $reason")
        val version = config.VERSION_NAME + "-" + config.BUILD_VERSION
        val profile = profileFunction.getProfile() ?: return
        val profileName = profileFunction.getProfileName()

        var apsResult: JSONObject? = null
        var iob: JSONObject? = null
        var enacted: JSONObject? = null
        lastRun?.let { lastRun ->
            if (lastRun.lastAPSRun > dateUtil.now() - 300 * 1000L) {
                // do not send if result is older than 1 min
                apsResult = lastRun.request?.json()?.also {
                    it.put("timestamp", dateUtil.toISOString(lastRun.lastAPSRun))
                    it.put("isfMgdlForCarbs", profile.getIsfMgdlForCarbs(dateUtil.now(), "LoopPlugin", config, processedDeviceStatusData))
                }
                iob = lastRun.request?.iob?.json(dateUtil)?.also {
                    it.put("time", dateUtil.toISOString(lastRun.lastAPSRun))
                }
                val requested = JSONObject()
                if (lastRun.tbrSetByPump?.enacted == true) { // enacted
                    enacted = lastRun.request?.json()?.also {
                        it.put("rate", lastRun.tbrSetByPump?.json(profile.getBasal())["rate"])
                        it.put("duration", lastRun.tbrSetByPump?.json(profile.getBasal())["duration"])
                        it.put("received", true)
                    }
                    requested.put("duration", lastRun.request?.duration)
                    requested.put("rate", lastRun.request?.rate)
                    requested.put("temp", "absolute")
                    requested.put("smb", lastRun.request?.smb)
                    enacted?.put("requested", requested)
                    enacted?.put("smb", lastRun.tbrSetByPump?.bolusDelivered)
                }
            }
        } ?: {
            val calcIob = iobCobCalculator.calculateIobArrayInDia(profile)
            if (calcIob.isNotEmpty()) {
                iob = calcIob[0].json(dateUtil)
                iob.put("time", dateUtil.toISOString(dateUtil.now()))
            }
        }
        persistenceLayer.insertDeviceStatus(
            DS(
                timestamp = dateUtil.now(),
                suggested = apsResult?.toString(),
                iob = iob?.toString(),
                enacted = enacted?.toString(),
                device = "openaps://" + Build.MANUFACTURER + " " + Build.MODEL,
                pump = activePlugin.activePump.getJSONStatus(profile, profileName, version).toString(),
                uploaderBattery = receiverStatusStore.batteryLevel,
                isCharging = receiverStatusStore.isCharging,
                configuration = runningConfiguration.configuration().toString()
            )
        )
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "loop_settings"
            title = rh.gs(app.aaps.core.ui.R.string.loop)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveListPreference(ctx = context, stringKey = StringKey.LoopApsMode, title = app.aaps.core.ui.R.string.aps_mode_title, entries = entries(), entryValues = entryValues()))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.LoopOpenModeMinChange, dialogMessage = R.string.loop_open_mode_min_change_summary, title = R.string.loop_open_mode_min_change))
        }
    }

    override fun entries() = arrayOf<CharSequence>(
        rh.gs(app.aaps.core.ui.R.string.closedloop),
        rh.gs(app.aaps.core.ui.R.string.openloop),
        rh.gs(app.aaps.core.ui.R.string.lowglucosesuspend),
    )

    override fun entryValues() = arrayOf<CharSequence>(
        ApsMode.CLOSED.name,
        ApsMode.OPEN.name,
        ApsMode.LGS.name,
    )

    companion object {

        private const val CHANNEL_ID = "AAPS-OpenLoop"
    }
}