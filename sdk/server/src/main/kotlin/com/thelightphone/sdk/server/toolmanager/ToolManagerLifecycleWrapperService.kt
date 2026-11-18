package com.thelightphone.sdk.server.toolmanager

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.toolmanager.HTTPS_PORT
import com.thelightphone.toolmanager.Logger
import com.thelightphone.toolmanager.PairingCode
import com.thelightphone.toolmanager.TetherEndpoint
import com.thelightphone.toolmanager.ToolManagerServiceAndroid
import com.thelightphone.toolmanager.ToolManagerServiceAndroid.ServerTransport
import com.thelightphone.toolmanager.TotpToolManagerAuth
import com.thelightphone.toolmanager.authenticatedIpUrl
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * The ToolManager's [ToolManagerServiceAndroid] is not a "real" Android service, it's lifecycle is Coroutine based
 * This just pairs it to an actual Android service, and ensures that the [ToolManagerActivity] is always showing
 * when the coroutine service is running.
 *
 * Runs over USB tethering when [LightSdkServer.provideUsbTethering] supplies a [UsbTethering] implementation,
 * otherwise falls back to Wi-Fi. In tether mode this service owns USB tethering for the run: it turns it on,
 * re-requests it after a replug, and turns it off when stopped.
 */
class ToolManagerLifecycleWrapperService : Service() {

    companion object {
        private const val TAG = "ToolManagerLifecycleWrapperService"

        // Unplugging ends USB tethering and replugging doesn't bring it back. After a connect, wait
        // this long before checking: starting tethering itself re-enumerates USB (a quick
        // disconnect/connect), which shouldn't trigger another request.
        const val DEFAULT_RETETHER_CHECK_DELAY_MS = 5_000L

        private const val EXTRA_RETETHER_CHECK_DELAY_MS = "retether_check_delay_ms"

        @Volatile
        private var serviceRunning = false

        @Volatile
        private var activeAuth: TotpToolManagerAuth? = null

        fun refreshAuth() {
            activeAuth?.invalidateKeyCache()
        }

        fun start(context: Context, retetherCheckDelayMs: Long = DEFAULT_RETETHER_CHECK_DELAY_MS) {
            serviceRunning = true
            context.startService(
                Intent(context, ToolManagerLifecycleWrapperService::class.java)
                    .putExtra(EXTRA_RETETHER_CHECK_DELAY_MS, retetherCheckDelayMs)
            )
        }

        fun stop(context: Context) {
            serviceRunning = false
            context.stopService(Intent(context, ToolManagerLifecycleWrapperService::class.java))
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ToolManagerLifecycleWrapperService =
            this@ToolManagerLifecycleWrapperService
    }

    private val binder = LocalBinder()
    private lateinit var toolManagerService: ToolManagerServiceAndroid

    private var usbTethering: UsbTethering? = null

    // Collects the tether status in tether mode; cancelled in onDestroy.
    private val scope = MainScope()

    private var latestLink: UsbTetherLink? = null

    /**
     * USB tether status, from the latest link state and whether the tool manager is serving on the
     * tether IP yet
     */
    val tetherStatus: UsbTetherStatus
        get() {
            val link = latestLink ?: return UsbTetherStatus.Disconnected
            val ipUrl = toolManagerService.getTetherEndpoint()?.authenticatedIpUrl
            return when {
                link.error != null -> link.error
                link.usbHostConnected && link.computerJoined && ipUrl != null -> UsbTetherStatus.ComputerConnected(
                    ipUrl
                )
                // a computer that's joined but has nothing to reach yet is still waiting
                link.usbHostConnected && (link.tetherInterfaceUp || link.computerJoined) -> UsbTetherStatus.WaitingForComputer
                link.usbHostConnected -> UsbTetherStatus.UsbConnected
                link.powered -> UsbTetherStatus.ChargingOnly
                else -> UsbTetherStatus.Disconnected
            }
        }

    private val autoForegroundCallback = object : Application.ActivityLifecycleCallbacks {
        // if Main activity is resumed, but the service is still running -> bring this activity back
        // we want to make sure if the service is running, the user is looking at ToolManagerActivity
        override fun onActivityResumed(activity: Activity) {
            if (serviceRunning) {
                LightSdkServer.foregroundToolManagerUi(activity, null)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private val logger = object : Logger {
        override fun log(tag: String, message: String) {
            Log.i(tag, message)
        }

        override fun reportError(
            tag: String,
            exception: Throwable?,
            message: String
        ) {
            LightSdkServer.reportError(tag, exception, message)
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceRunning = true
        application.registerActivityLifecycleCallbacks(autoForegroundCallback)
        toolManagerService = ToolManagerServiceAndroid(
            LightSdkServer.rootToolManagerDataProvider(),
            this,
            logger,
            port = HTTPS_PORT,
            enableLogging = LightSdkServer.verboseLoggingEnabled,
            onNetworkNeedsApproval = { LightSdkServer.isNetworkApprovedForToolManager(it) },
            provideNewAuth = {
                TotpToolManagerAuth(
                    keyDirectory = LightSdkServer.getApkInboxAuthDirectory(this),
                    cipher = LightSdkServer.getToolManagerKeyCipher()
                ).also { activeAuth = it }
            }
        )
        usbTethering = LightSdkServer.provideUsbTethering(this)
        Log.i(TAG, "Tool manager transport: ${if (usbTethering != null) "USB tether" else "Wi-Fi"}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!toolManagerService.isRunning) {
            // In tether mode the server doesn't need a network to start: its tether server comes
            // up whenever the USB interface does, so the cable can be plugged in afterwards.
            val tethering = usbTethering
            if (tethering != null) {
                val retetherCheckDelayMs = intent?.getLongExtra(
                    EXTRA_RETETHER_CHECK_DELAY_MS,
                    DEFAULT_RETETHER_CHECK_DELAY_MS
                ) ?: DEFAULT_RETETHER_CHECK_DELAY_MS
                tethering.start()
                scope.launch {
                    // collectLatest: a newer link cancels a pending re-tether wait.
                    tethering.link.collectLatest { link ->
                        latestLink = link
                        // A host on the cable but no tethering (maybe after a replug). We own USB
                        // tethering in this mode, even a session left over from a killed process.
                        if (link.usbHostConnected && !link.tetherInterfaceUp && !link.computerJoined && link.error == null) {
                            delay(retetherCheckDelayMs)
                            Log.i(TAG, "USB reconnected without tethering; requesting it again")
                            tethering.start()
                        }
                    }
                }
            }
            val transport = if (tethering != null) {
                ServerTransport.UsbTether(tethering.hostname)
            } else {
                ServerTransport.Wifi
            }
            val started = toolManagerService.start(transport)
            if (!started) {
                Log.e(TAG, "Tool manager failed to start")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceRunning = false
        application.unregisterActivityLifecycleCallbacks(autoForegroundCallback)
        scope.cancel()
        toolManagerService.stop()
        usbTethering?.stop()
        latestLink = null
        activeAuth = null
        Log.d(TAG, "onDestroy: tool manager stopped")
    }

    fun getHttpsUrl(hostOverride: InetAddress? = null, path: String? = null): String? =
        toolManagerService.getHttpsUrl(hostOverride, path)

    /** Where a computer on the USB cable reaches the tool manager, IP address / mDNS entry*/
    val tetherEndpoint: TetherEndpoint? get() = toolManagerService.getTetherEndpoint()

    /** Current code for pairing a browser that opened the URL without a key. */
    val pairingCode: PairingCode? get() = toolManagerService.currentPairingCode()

    val isRunning: Boolean get() = toolManagerService.isRunning
}
