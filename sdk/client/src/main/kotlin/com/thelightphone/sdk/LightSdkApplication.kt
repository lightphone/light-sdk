package com.thelightphone.sdk

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.room.Room
import com.thelightphone.sdk.shared.LightConstants
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

open class LightSdkApplication : Application() {

    companion object {
        private const val TAG = "LightSdkApplication"

        private val _lightOSData = MutableStateFlow(LightServerData(null))
        val lightOsData: StateFlow<LightServerData> = _lightOSData.asStateFlow()
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val serverPackage = readServerPackage()
        invokeEntryPoint()
        registerWithLightServer(serverPackage)
        LightServiceConnection.bind(this, serverPackage)
    }

    @Suppress("DEPRECATION")
    private fun readServerPackage(): String {
        val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        return requireNotNull(appInfo.metaData?.getString("com.thelightphone.sdk.LIGHT_SERVER_PACKAGE")) {
            "LIGHT_SERVER_PACKAGE not found in manifest — set tool.serverPackage in lighttool.toml"
        }
    }

    // Tool may have registered an initialization function, call it
    private fun invokeEntryPoint() {
        val entryPoint = LightSdkRegistry.entryPoint ?: return
        applicationScope.launch {
            entryPoint.onToolCreate(lightOsData)
        }
    }

    private fun registerWithLightServer(serverPackage: String) {
        applicationScope.launch {
            LightPushManager(this@LightSdkApplication).pushCredentialsFlow.collect { credentials ->
                _lightOSData.update { it.copy(pushCredentials = credentials) }
            }
        }

        // Force unified push to use our distributor
        UnifiedPush.saveDistributor(this, serverPackage)

        // Local channel — direct IPC between LightOS/emulator and this tool
        UnifiedPush.register(
            this,
            instance = LightConstants.PUSH_INSTANCE_LOCAL,
            messageForDistributor = LightConstants.PUSH_CHANNEL_LOCAL,
        )

        if (LightSdkRegistry.entryPoint?.enablePushNotifications == true) {
            // Remote channel — real push notifications from the tool's backend
            UnifiedPush.register(
                this,
                instance = LightConstants.PUSH_INSTANCE_REMOTE,
                vapid = BuildConfig.LIGHT_VAPID_KEY.ifEmpty { null },
                messageForDistributor = LightConstants.PUSH_CHANNEL_REMOTE,
            )
        }
    }
}
