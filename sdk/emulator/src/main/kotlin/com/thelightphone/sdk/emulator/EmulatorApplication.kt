package com.thelightphone.sdk.emulator

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import androidx.work.Configuration
import com.thelightphone.backup.BackupScheduler
import com.thelightphone.backup.BackupWorkerFactory
import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.sdk.emulator.backup.DefaultBackupDependencyProvider
import com.thelightphone.sdk.emulator.backup.dummyCloudBackupLeafView
import com.thelightphone.toolmanager.BranchView
import com.thelightphone.toolmanager.RootViewSpec
import com.thelightphone.toolmanager.datatree.RootDataTree
import com.thelightphone.toolmanager.datatree.StaticBranchProvider
import com.thelightphone.sdk.emulator.http.EmulatorHttpServer
import com.thelightphone.sdk.server.DefaultLightSdkServerSettings
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.server.backup.BackupPackageAllowList
import com.thelightphone.sdk.server.backup.BackupScheduleSyncer
import com.thelightphone.sdk.server.backup.DefaultBackupSettingsViewModel
import com.thelightphone.sdk.server.backup.DefaultObservableBackupPreferences
import com.thelightphone.sdk.server.backup.InternalToolBackupSpec
import com.thelightphone.sdk.server.backup.webDavBackupLeafView
import com.thelightphone.sdk.server.toolmanager.developerModeDataView
import com.thelightphone.sdk.server.toolmanager.getApkInboxSignaturesDirectory
import com.thelightphone.sdk.server.toolmanager.readSigningKeyHash
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.toolmanager.DiscoveredToolsBranchProvider
import com.thelightphone.toolmanager.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// SHA-256 fingerprint of sdk/keys/lightsdk-dev.jks (alias: lightsdk-dev).
private const val LIGHTSDK_DEV_CERT_SHA256 =
    "B9C33E29B0CCAD2BFF11ACAB55F65A3C517EF4BC92CD9C77785366FA353D5F28"

private const val BACKUP_ROOT_PATH = "backup"

class EmulatorApplication : Application(), Configuration.Provider {
    companion object {
        // bumped whenever installed tools may have changed (e.g. an APK finished installing via
        // the SDK), so MainActivity's tool list can re-fetch. safe to update from any thread.
        private val _installedToolsRefreshCount = MutableStateFlow(0)
        val installedToolsRefreshCount = _installedToolsRefreshCount.asStateFlow()
    }

    val lightAudioManager by lazy { LightAudioManager(this) }
    val deviceKeyHandler by lazy { EmulatorDeviceKeyHandler(lightAudioManager) }

    val backupSharedPrefs: SharedPreferences by lazy {
        getSharedPreferences(
            "emulator-backup",
            MODE_PRIVATE
        )
    }

    val backupObservablePreferences by lazy { DefaultObservableBackupPreferences(backupSharedPrefs) }

    val settings by lazy { DefaultLightSdkServerSettings(this) }

    private val backupDependencyProvider by lazy {
        val cipher = LightSdkServer.getToolManagerKeyCipher()
        DefaultBackupDependencyProvider(this, toolManagerLogger, cipher, BACKUP_ROOT_PATH)
    }

    private val backupPackageAllowList by lazy {
        BackupPackageAllowList(backupSharedPrefs)
    }

    val backupSettingsViewModel by lazy {
        object :
            DefaultBackupSettingsViewModel(backupObservablePreferences, backupPackageAllowList) {
            override suspend fun getBackupCapablePackages() =
                backupDependencyProvider.getBackupCapableTools()

            override fun labelForPackage(packageName: String) =
                LightSdkServer.getToolLabel(packageName).toString()

            override fun kickoffBackupWorker() =
                BackupScheduler.enqueueImmediateBackup(this@EmulatorApplication)

            override suspend fun removeStoredCredentials(provider: RemoteBackupProvider) {
                when (provider) {
                    RemoteBackupProvider.WebDav -> backupDependencyProvider.webDavCredentialStorage.removeWebDavCredentials()
                    else -> backupDependencyProvider.tokenStorage.removeOAuthDetails(provider.name)
                }
            }

            override suspend fun cancelBackupWork() {
                BackupScheduler.cancelPeriodic(this@EmulatorApplication)
                BackupScheduler.cancelImmediate(this@EmulatorApplication)
            }

            override fun launchToolManagerForLogin(activity: Activity) {
                LightSdkServer.foregroundToolManagerUi(activity, BACKUP_ROOT_PATH)
            }
        }
    }

    @Volatile
    private var validSignatures: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        val mollySocketUriString = BuildConfig.MOLLYSOCKET_URI
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
            foregroundToolManagerUi = { activity, path ->
                if (activity !is ToolManagerActivity) {
                    val intent = Intent(activity, ToolManagerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(LightSdkServer.TOOL_MANAGER_PATH_EXTRA, path)
                    activity.startActivity(intent)
                }
            }
            rootToolManagerDataProvider = ::buildToolManagerRoot
            canBeUninstalledByToolManager = { true }
            onApkUninstalled = {
                updateValidSignatures()
                _installedToolsRefreshCount.value++
            }
            onApkInstalled = {
                updateValidSignatures()
                backupSettingsViewModel.refreshAllowList()
                _installedToolsRefreshCount.value++
            }
            getToolLabel = { packageName ->
                // internal tools don't use typical android package names, check matches here first
                InternalToolBackupSpec.fromPackageName(packageName)?.defaultLabel
                    ?: try {
                        packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager)
                    } catch (_: PackageManager.NameNotFoundException) {
                        packageName
                    }
            }

            canBackUpFromPackage = { tool -> backupPackageAllowList.userAllowedTool(tool) }
            getAlbumPhotoBackupDirs = {
                listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES))
            }
            getAlbumVideoBackupDirs = {
                listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
            }

            with(EmulatorLocationHelper) { initLocationHelper(this@EmulatorApplication) }
        }
        updateValidSignatures()
        EmulatorHttpServer(this).start()

        val backupScheduleSyncer = BackupScheduleSyncer(this, backupObservablePreferences)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { backupScheduleSyncer.run() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                BackupWorkerFactory(
                    backupObservablePreferences,
                    backupDependencyProvider
                )
            )
            .build()

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

    private val toolManagerLogger = object : Logger {
        override fun log(tag: String, message: String) {
            Log.d(tag, message)
        }

        override fun reportError(
            tag: String,
            exception: Throwable?,
            message: String
        ) {
            Log.e(tag, message, exception)
        }
    }

    private fun buildToolManagerRoot(): RootDataTree {
        return RootDataTree {
            val developerMode =
                developerModeDataView(this, LightSdkServer.getToolManagerKeyCipher())
            val onBackupLinked: suspend (RemoteBackupProvider) -> Unit = {
                backupObservablePreferences.setActiveProvider(it)
            }
            val backupBranch = BranchView(
                RootViewSpec("Backup", BACKUP_ROOT_PATH),
                StaticBranchProvider(
                    listOf(
                        dummyCloudBackupLeafView(
                            backupDependencyProvider.tokenStorage,
                            BuildConfig.BACKUP_WORKER_HOST,
                            onLinked = onBackupLinked
                        ),
                        webDavBackupLeafView(
                            backupDependencyProvider.webDavCredentialStorage,
                            onLinked = onBackupLinked
                        ),
                    )
                )
            )
            val toolBranch = DiscoveredToolsBranchProvider(this, toolManagerLogger) {
                LightSdkServer.isPackageAllowed(settings.clientFilterLevel, this, it)
            }
            BranchView(
                RootViewSpec("root", ""),
                StaticBranchProvider(listOf(developerMode, backupBranch) + toolBranch.getChildren())
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