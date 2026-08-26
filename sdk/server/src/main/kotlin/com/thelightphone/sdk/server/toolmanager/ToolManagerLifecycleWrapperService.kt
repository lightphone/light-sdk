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
import com.thelightphone.toolmanager.ToolManagerServiceAndroid
import com.thelightphone.toolmanager.HTTPS_PORT
import com.thelightphone.toolmanager.Logger
import com.thelightphone.toolmanager.TotpToolManagerAuth
import com.thelightphone.sdk.server.LightSdkServer
import java.net.InetAddress

/**
 * The ToolManager's [ToolManagerServiceAndroid] is not a "real" Android service, it's lifecycle is Coroutine based
 * This just pairs it to an actual Android service, and ensures that the [ToolManagerActivity] is always showing
 * when the coroutine service is running.
 */
class ToolManagerLifecycleWrapperService : Service() {

    companion object {
        private const val TAG = "ToolManagerLifecycleWrapperService"

        @Volatile
        private var serviceRunning = false

        @Volatile
        private var activeAuth: TotpToolManagerAuth? = null

        fun refreshAuth() {
            activeAuth?.invalidateKeyCache()
        }

        fun start(context: Context) {
            serviceRunning = true
            context.startService(Intent(context, ToolManagerLifecycleWrapperService::class.java))
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

    private val autoForegroundCallback = object : Application.ActivityLifecycleCallbacks {
        // if Main activity is resumed, but the service is still running -> bring this activity back
        // we want to make sure if the service is running, the user is looking at ToolManagerActivity
        override fun onActivityResumed(activity: Activity) {
            if (serviceRunning) {
                LightSdkServer.foregroundToolManagerUi(activity)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!toolManagerService.isRunning) {
            val started = toolManagerService.start()
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
        toolManagerService.stop()
        activeAuth = null
        Log.d(TAG, "onDestroy: tool manager stopped")
    }

    fun getHttpsUrl(hostOverride: InetAddress? = null): String? = toolManagerService.getHttpsUrl(hostOverride)

    val isRunning: Boolean get() = toolManagerService.isRunning
}
