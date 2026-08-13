package com.thelightphone.sdk.server.filemanager

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.thelightphone.filemanager.FileManagerServiceAndroid
import com.thelightphone.filemanager.HTTPS_PORT
import com.thelightphone.filemanager.Logger
import com.thelightphone.filemanager.TotpFileManagerAuth
import com.thelightphone.sdk.server.LightSdkServer
import java.net.InetAddress

/**
 * The FileManager's [FileManagerServiceAndroid] is not a "real" Android service, it's lifecycle is Coroutine based
 * This just pairs it to an actual Android service, and ensures that the [FileManagerActivity] is always showing
 * when the coroutine service is running.
 */
class FileManagerLifecycleWrapperService : Service() {

    companion object {
        private const val TAG = "FileManagerLifecycleWrapperService"

        @Volatile
        private var serviceRunning = false

        fun start(context: Context) {
            serviceRunning = true
            context.startService(Intent(context, FileManagerLifecycleWrapperService::class.java))
        }

        fun stop(context: Context) {
            serviceRunning = false
            context.stopService(Intent(context, FileManagerLifecycleWrapperService::class.java))
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): FileManagerLifecycleWrapperService =
            this@FileManagerLifecycleWrapperService
    }

    private val binder = LocalBinder()
    private lateinit var fileManagerService: FileManagerServiceAndroid

    private val autoForegroundCallback = object : Application.ActivityLifecycleCallbacks {
        // if Main activity is resumed, but the service is still running -> bring this activity back
        // we want to make sure if the service is running, the user is looking at FileManagerActivity
        override fun onActivityResumed(activity: Activity) {
            if (serviceRunning) {
                LightSdkServer.foregroundFileManagerUi(activity)
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
        fileManagerService = FileManagerServiceAndroid(
            LightSdkServer.rootFileManagerDataProvider(),
            this,
            logger,
            port = HTTPS_PORT,
            enableLogging = LightSdkServer.verboseLoggingEnabled,
            provideNewAuth = {
                TotpFileManagerAuth(
                    keyDirectory = LightSdkServer.getApkInboxAuthDirectory(this),
                    cipher = LightSdkServer.getFileManagerKeyCipher()
                )
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!fileManagerService.isRunning) {
            val started = fileManagerService.start()
            if (!started) {
                Log.e(TAG, "File manager failed to start")
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
        fileManagerService.stop()
        Log.d(TAG, "onDestroy: file manager stopped")
    }

    fun getHttpsUrl(hostOverride: InetAddress? = null): String? = fileManagerService.getHttpsUrl(hostOverride)

    val isRunning: Boolean get() = fileManagerService.isRunning
}
