package com.thelightphone.sdk.emulator

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import com.thelightphone.toolmanager.BranchView
import com.thelightphone.toolmanager.RootViewSpec
import com.thelightphone.toolmanager.datatree.RootDataTree
import com.thelightphone.toolmanager.datatree.StaticBranchProvider
import com.thelightphone.sdk.emulator.http.EmulatorHttpServer
import com.thelightphone.sdk.server.DefaultLightSdkServerSettings
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.server.toolmanager.developerModeDataView
import com.thelightphone.sdk.server.toolmanager.getApkInboxSignaturesDirectory
import com.thelightphone.sdk.server.toolmanager.readSigningKeyHash
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightModalManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// SHA-256 fingerprint of sdk/keys/lightsdk-dev.jks (alias: lightsdk-dev).
private const val LIGHTSDK_DEV_CERT_SHA256 =
    "B9C33E29B0CCAD2BFF11ACAB55F65A3C517EF4BC92CD9C77785366FA353D5F28"

class EmulatorApplication : Application() {
    companion object {
        // bumped whenever installed tools may have changed (e.g. an APK finished installing via
        // the SDK), so MainActivity's tool list can re-fetch. safe to update from any thread.
        private val _installedToolsRefreshCount = MutableStateFlow(0)
        val installedToolsRefreshCount = _installedToolsRefreshCount.asStateFlow()
    }

    val lightAudioManager by lazy { LightAudioManager(this) }
    val deviceKeyHandler by lazy { EmulatorDeviceKeyHandler(lightAudioManager) }

    @Volatile
    private var validSignatures: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        val mollySocketUriString = BuildConfig.MOLLYSOCKET_URI
        val settings = DefaultLightSdkServerSettings(this)

        with(LightSdkServer) {
            registerLockReceiver(MainActivity::class.java, settings)
            customServiceMethodResolver = { _, methodId, _ ->
                if (methodId == "GetMollySocketUri" && mollySocketUriString.isNotEmpty()) {
                    val json = "{\"mollySocketUri\":\"$mollySocketUriString\"}"
                    LightResult.Success(json)
                } else {
                    LightResult.Error(LightResult.ErrorCode.Unknown)
                }
            }
            val pushDomain = BuildConfig.PUSH_DOMAIN.ifEmpty { "http://localhost:8090" }
            pushEndpointFetcher = { _, token, vapid ->
                Log.d("LightEmulator", "getting push endpoint for token: $token, vapid: $vapid")
                "$pushDomain/push/$token"
            }
            isLightSigned =
                { _, signature -> validSignatures.any { it.equals(signature, ignoreCase = true) } }
            isLightApproved = { _, _ -> false }
            provideSdkSettings = { settings }
            permissionActivity = LightSdkPermissionActivity::class.java
            onDeviceKeyEvent = { _, request -> handleDeviceKeyEvent(request) }
            // emulator is running on local device, automatically approve network
            isNetworkApprovedForToolManager = { true }
            foregroundToolManagerUi = { activity ->
                if (activity !is ToolManagerActivity) {
                    val intent = Intent(activity, ToolManagerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                }
            }
            rootToolManagerDataProvider = ::buildToolManagerRoot
            onApkInstalled = {
                updateValidSignatures()
                _installedToolsRefreshCount.value++
            }
        }
        updateValidSignatures()
        EmulatorHttpServer(this).start()
    }

    // For the emulator, any apk built with LIGHTSDK_DEV_CERT_SHA256 is considered signed by Light
    // also include user uploaded signatures
    private fun updateValidSignatures() {
        val signatures = mutableListOf(LIGHTSDK_DEV_CERT_SHA256)
        val userAdded = LightSdkServer.getApkInboxSignaturesDirectory(this)
            .listFiles { it.isFile }
            ?.mapNotNull { it.readSigningKeyHash() }
            .orEmpty()
        validSignatures = signatures + userAdded
    }

    private fun buildToolManagerRoot(): RootDataTree {
        return RootDataTree {
            val developerMode = developerModeDataView(this, LightSdkServer.getToolManagerKeyCipher())
            BranchView(
                RootViewSpec("root", ""),
                StaticBranchProvider(listOf(developerMode))
            )
        }
    }

    private fun handleDeviceKeyEvent(request: LightServiceMethod.DeviceKeyEvent.Request) {
        deviceKeyHandler.onDeviceKeyEventRequest(request)?.let { modal ->
            // when the server is done handling the key press, server should re-foreground
            // the app that sent it, if desired
            fun relaunchSender() {
                val componentToRelaunch =
                    request.componentToRelaunch?.let(ComponentName::unflattenFromString)
                startActivity(
                    Intent().setComponent(componentToRelaunch)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                EmulatorNavController.navigateTo(Nav.Toolbox)
            }

            val keyModal = DeviceKeyModal(
                modal,
                onMoreClick = {
                    EmulatorNavController.navigateTo(Nav.Settings(backButtonOverride = ::relaunchSender))
                },
                onExpired = { relaunchSender() }
            )
            // show the modal, and then pull this app into focus over the client
            LightModalManager.show(keyModal)
            foregroundEmulator()
        }
    }

    private fun foregroundEmulator() {
        val intent = Intent(this.applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}