package com.thelightphone.sdk.server

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.util.Log
import com.thelightphone.sdk.digest.sha256Hex
import com.thelightphone.sdk.server.backup.BackupCapableTool
import com.thelightphone.sdk.server.backup.SmsBackupExclusion
import com.thelightphone.sdk.server.toolmanager.UsbTethering
import com.thelightphone.sdk.shared.LightConstants
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.trust.*
import com.thelightphone.toolmanager.AndroidKeystoreKeyCipher
import com.thelightphone.toolmanager.BranchView
import com.thelightphone.toolmanager.DataView
import com.thelightphone.toolmanager.KeyCipher
import com.thelightphone.toolmanager.LocalNetworkInfo
import com.thelightphone.toolmanager.RootViewSpec
import com.thelightphone.toolmanager.datatree.BranchDataTree
import com.thelightphone.toolmanager.datatree.RootDataTree
import java.io.File

data class InstalledClient(val packageInfo: PackageInfo, val sdkVersion: String)

object LightSdkServer {
    /** The host opens one store per persistence backend and registers it during startup. */
    var trustStore: TrustStore = UnavailableTrustStore

    /** The host may configure a verifier, trust state source, and admission store at startup. */
    @Volatile var toolTrustRuntime: ToolTrustEvaluation = UnavailableToolTrustEvaluation

    /** Evaluates [meta] at [level] through [toolTrustRuntime]. */
    fun evaluateTool(level: ClientFilterLevel, meta: ApkMetadata): ToolEvaluation =
        toolTrustRuntime.evaluate(meta, level)

    /**
     * Looks up [packageName]'s current [PackageInfo] and evaluates it at [level].
     *
     * failed lookups (i.e: package was just uninstalled) fallback to [unavailableOrAllowAll], so
     * that [ClientFilterLevel.AllowAllApks] applies.
     */
    fun evaluateTool(
        level: ClientFilterLevel,
        context: Context,
        packageName: String,
    ): ToolEvaluation =
        try {
            evaluateTool(
                level,
                readApkMetadata(
                    context.packageManager.getPackageInfo(
                        packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES,
                    )
                ),
            )
        } catch (_: Exception) {
            unavailableOrAllowAll(level, DenyReason.ArtifactUnavailable)
        }

    val clientAuthorizations = SdkClientAuthorizations()
    val toolResources = ToolResourceRegistry()
    @Volatile internal var toolEnforcement: ToolEnforcement = NoToolEnforcement

    var onRevokeToolResources: (Context, String) -> Unit = { _, _ ->
        error("Host resource cleanup is not configured")
    }
    var onRevokeToolUid: (Int) -> Unit = {}

    fun notifyToolTrustChanged() {
        toolEnforcement.requestReconciliation()
    }

    /** An allowed evaluation still needs a permit from the configured enforcement policy. */
    private fun enforcementAllows(result: ToolEvaluation, packageName: String): Boolean =
        result.decision == LightInstallDecision.Allow &&
            toolEnforcement.permitsAccess(packageName, result)

    private const val TAG = "LightSdkServer"
    const val SCREEN_OFF_FLAG = "SCREEN_OFF"
    const val TOOL_MANAGER_PATH_EXTRA = "TOOL_MANAGER_PATH"

    val Context.runningAsSystemApp: Boolean
        get() {
            val isSystemUid = (Process.myUid() == Process.SYSTEM_UID)
            val isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            return isSystemApp && isSystemUid
        }

    var isSdkVersionSupported: (sdkVersion: String) -> Boolean = { true }

    /** Keeps only the installed clients allowed to run under [settings]'s current filter level. */
    fun List<InstalledClient>.filterAllowedInstalledTools(
        settings: LightSdkServerSettings
    ): List<InstalledClient> {
        val clientFilterLevel = settings.clientFilterLevel
        return filter {
            val meta = readApkMetadata(it.packageInfo)
            isPackageAllowed(clientFilterLevel, meta)
        }
    }

    /** checks whether a (already installed) package is allowed at [clientFilterLevel] */
    fun isPackageAllowed(clientFilterLevel: ClientFilterLevel, packageMeta: ApkMetadata): Boolean {
        return enforcementAllows(
            evaluateTool(clientFilterLevel, packageMeta),
            packageMeta.packageName,
        )
    }

    /**
     * checks whether a (already installed) package is allowed at [clientFilterLevel]
     *
     * convenience method that looks up ApkMetadata from [callingPackage] name
     */
    fun isPackageAllowed(
        clientFilterLevel: ClientFilterLevel,
        context: Context,
        callingPackage: String,
    ): Boolean {
        return enforcementAllows(
            evaluateTool(clientFilterLevel, context, callingPackage),
            callingPackage,
        )
    }

    /**
     * checks whether the installation of an [apkFile] in disk is permitted at [clientFilterLevel]
     *
     * also returns false when the apk file can't be parsed
     */
    fun isPackageInstallable(
        clientFilterLevel: ClientFilterLevel,
        context: Context,
        apkFile: File,
    ): Boolean {
        val meta = context.readApkMetadata(apkFile) ?: return false
        return evaluateTool(clientFilterLevel, meta).decision == LightInstallDecision.Allow
    }

    fun Context.queryInstalledClients(): List<InstalledClient> {
        val marker = Intent(LightConstants.ACTION_SDK_MARKER)

        val results =
            packageManager.queryBroadcastReceivers(
                marker,
                PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS,
            )

        return results
            .map {
                val packageName = it.activityInfo.packageName
                val packageInfo: PackageInfo
                try {
                    packageInfo =
                        packageManager.getPackageInfo(
                            packageName,
                            PackageManager.GET_PERMISSIONS or
                                PackageManager.GET_SIGNING_CERTIFICATES,
                        )
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(TAG, "Could not find SDK package", e)
                    return@map null
                }
                val meta =
                    it.activityInfo.metaData
                        ?: run {
                            Log.e(TAG, "SDK client didn't provide metadata: $packageName")
                            return@map null
                        }
                val sdkVersion =
                    meta.getString(LightConstants.SDK_VERSION_KEY)
                        ?: run {
                            Log.e(TAG, "SDK client didn't provide sdkVersion: $packageName")
                            return@map null
                        }

                InstalledClient(packageInfo, sdkVersion)
            }
            .filterNotNull()
    }

    fun Context.queryEnabledClients(settings: LightSdkServerSettings): List<InstalledClient> {
        return queryInstalledClients()
            .filter { isSdkVersionSupported(it.sdkVersion) }
            .filterAllowedInstalledTools(settings)
    }

    var getApkInboxDirectory: (Context) -> File = {
        File(it.applicationContext.filesDir, "apkInbox")
    }

    var getToolManagerKeyCipher: () -> KeyCipher = { AndroidKeystoreKeyCipher() }

    var provideSdkSettings: (Context) -> LightSdkServerSettings = {
        DefaultLightSdkServerSettings(it)
    }

    var defaultClientFilterLevel: ClientFilterLevel = ClientFilterLevel.AllowLightApprovedApks

    /**
     * defines the behavior for when the server app should "foreground" itself over other running
     * apps LightOS is aggressive about this by default. It will foreground itself for all alerts,
     * and whenever the phone is locked.
     */
    var defaultForceFocusLevel: ForceFocusLevel = ForceFocusLevel.Always

    /**
     * return the POST endpoint that the calling tool's application server should use to get
     * UnifiedPush through Light's server, down to LightOS/emulator, then over to Tool
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var pushEndpointFetcher: (callingPackage: String, token: String, vapid: String?) -> String? =
        { callingPackage, token, vapid ->
            Log.e(TAG, "no endpoint fetch function provided - defaulting to localhost.")
            "https://localhost/$token"
        }

    /**
     * handle custom requests from clients that are "privileged"/in-development
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var customServiceMethodResolver:
        (callingId: Int, methodId: String, payload: String?) -> LightResult<String> =
        { callingId, methodId, payload ->
            Log.e(TAG, "Service method $methodId not found!")
            LightResult.Error(LightResult.ErrorCode.Unknown, "unknown method: $methodId")
        }

    /**
     * Handle a hardware key event forwarded from a client whose current screen did not consume it.
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var onDeviceKeyEvent:
        (callingUid: Int, event: LightServiceMethod.DeviceKeyEvent.Request) -> Unit =
        { _, event ->
            Log.w(TAG, "Unhandled device key event: $event")
        }

    /**
     * Open the phone dialer for [phoneNumber]
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var onOpenDialer: (callingUid: Int, phoneNumber: String) -> Unit = { _, phoneNumber ->
        Log.e(TAG, "OpenDialer not configured by server: phoneNumber=$phoneNumber")
    }

    /**
     * Return the best current location fix available to the server, or empty coordinates
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var onGetCurrentLocation:
        (callingUid: Int) -> LightResult<LightServiceMethod.GetCurrentLocation.Response> =
        { _ ->
            Log.e(TAG, "GetCurrentLocation not configured by server")
            LightResult.Error(LightResult.ErrorCode.Unknown, "GetCurrentLocation not configured")
        }

    /**
     * Returns the device's saved default location, or empty coordinates
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var onGetDefaultLocation:
        (callingUid: Int) -> LightResult<LightServiceMethod.GetDefaultLocation.Response> =
        { _ ->
            Log.e(TAG, "GetDefaultLocation not configured by server")
            LightResult.Error(LightResult.ErrorCode.Unknown, "GetDefaultLocation not configured")
        }

    /**
     * Requests a 30 second lease for location updates
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var onRequestLocationUpdates: (callingUid: Int) -> LightResult<Unit> = { _ ->
        Log.e(TAG, "RequestLocationUpdates not configured by server")
        LightResult.Error(LightResult.ErrorCode.Unknown, "RequestLocationUpdates not configured")
    }

    /**
     * Releases any previously requested location update lease
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var onReleaseLocationUpdates: (callingUid: Int) -> LightResult<Unit> = { _ ->
        Log.e(TAG, "ReleaseLocationUpdates not configured by server")
        LightResult.Error(LightResult.ErrorCode.Unknown, "ReleaseLocationUpdates not configured")
    }

    /**
     * Whether [LightNotificationListenerService] may bring this tool forward for an alert it
     * posted. Only asked for allowed tools that declare the alerts capability. The enclosing app
     * can override this for do-not-disturb, per-tool mutes or rate limits.
     */
    var allowAlertForeground: (packageName: String) -> Boolean = { true }

    /**
     * Called with every app's active notifications when the notification listener connects, and
     * again whenever a notification is posted, updated or removed. Runs on the main thread.
     * Settable from enclosing application, e.g. to show which tools have notifications.
     */
    var onActiveNotificationsChanged: (active: List<StatusBarNotification>) -> Unit = {}

    /**
     * Returns the package of the app that handles full-screen text entry, or null if there isn't
     * one. Tools ask for it through GetKeyboardPackage.
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var getKeyboardPackage: () -> String? = { null }

    var foregroundSelfWithCallback: (componentToReturnTo: ComponentName) -> Unit = {
        Log.e(TAG, "Server wants to foreground itself but does not know how!")
    }

    var canBeUninstalledByToolManager: (packageName: String) -> Boolean = { false }

    /**
     * Given an android permission id (android.manifest.CAMERA, for example), return whether this
     * server instance is allowed to grant that permission to the calling package
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var androidPermissionAllowed: (callingUid: Int, permissionName: String) -> Boolean =
        { _, permissionName ->
            // default grantable permissions; enclosing app may override
            setOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
                .contains(permissionName)
        }

    /**
     * Returns the user-visible label for a tool. Overrideable in case we want users to be able to
     * create custom labels for tools
     */
    var getToolLabel: (packageName: String) -> CharSequence = {
        Log.w(TAG, "getToolLabel is unset in LightSdkServer, falling back to package name")
        it
    }

    /**
     * Returns whether or not ToolManager nodes for a given package should be backed up to remote
     * storage Note that packages passed here have likely already been passed through the
     * `isPackageAllowed` filter
     */
    var canBackUpFromPackage: (backupCapableTool: BackupCapableTool) -> Boolean = { true }

    /**
     * Directories whose photos should be included in album backups. Files are backed up from the
     * top level of each directory (no recursion). Empty means photos aren't backed up.
     */
    var getAlbumPhotoBackupDirs: () -> List<File> = { emptyList() }

    /**
     * Directories whose videos should be included in album backups. Files are backed up from the
     * top level of each directory (no recursion). Empty means videos aren't backed up.
     */
    var getAlbumVideoBackupDirs: () -> List<File> = { emptyList() }

    /**
     * SMS rows that should be left out of message backups (e.g. placeholder rows the enclosing app
     * writes for its own bookkeeping). They're also ignored when working out how far back the first
     * backup needs to go. Null means every SMS is backed up.
     *
     * Here because LightOS stores some metadata in the sms table using "impossible" timestamps,
     * dont want to back those up
     */
    var getSmsBackupExclusion: () -> SmsBackupExclusion? = { null }

    /**
     * Called when the SDK server successfully installs a tool May be called with a null path if
     * unknown apk was installed (or we want to notify the server that it should refresh it's apk
     * list)
     */
    var onApkInstalled: (apkPath: String?) -> Unit = { apkPath ->
        if (!apkPath.isNullOrBlank()) {
            Log.d(TAG, "Successfully installed APK: $apkPath")
        }
    }

    /** Called when the SDK server successfully uninstalls a tool */
    var onApkUninstalled: (packageName: String) -> Unit = { packageName ->
        Log.d(TAG, "Successfully uninstalled package: $packageName")
    }

    /**
     * SDK server should have a singleTask Activity to represent the ToolManager's UI If the
     * ToolManager is running and the server app is active, the ToolManager UI should always show
     * [path] is an optional deep link into the ToolManager UI (e.g. a specific tool's page).
     */
    var foregroundToolManagerUi: (activityToReplace: Activity, path: String?) -> Unit = { _, _ ->
        // if activityToReplace is not the ToolManager UI already, relaunch the ToolManager UI
    }

    /**
     * USB tethering for the tool manager (see [UsbTethering]). Needs platform APIs, so the host app
     * provides it. Null means no USB available, and the tool manager runs over Wi-Fi.
     */
    var provideUsbTethering: (Context) -> UsbTethering? = { null }

    var isNetworkApprovedForToolManager: (LocalNetworkInfo) -> Boolean = {
        // by default don't trust any network. This MUST be overridden
        false
    }

    var reportError: (tag: String, exception: Throwable?, message: String) -> Unit =
        { tag, e, msg ->
            Log.e(tag, msg, e)
        }

    var verboseLoggingEnabled = true

    var rootToolManagerDataProvider: () -> RootDataTree = {
        RootDataTree {
            val stubDataTree =
                object : BranchDataTree {
                    override suspend fun getChildren(): List<DataView<*>> = emptyList()
                }
            BranchView(RootViewSpec("Empty Root", "emptyRoot"), stubDataTree, forceHide = true)
        }
    }

    /** Activity to launch when allowing user to grant permission via server app */
    var permissionActivity: Class<out Activity>? = null

    var grantPermission:
        (context: Context, packageName: String, permission: String) -> Result<Unit> =
        { context, packageName, permission ->
            runCatching {
                // Fine for emulator, can avoid reflection on real system apps
                val grant =
                    PackageManager::class
                        .java
                        .getMethod(
                            "grantRuntimePermission",
                            String::class.java,
                            String::class.java,
                            UserHandle::class.java,
                        )
                grant.invoke(
                    context.packageManager,
                    packageName,
                    permission,
                    Process.myUserHandle(),
                )
            }
        }

    /**
     * Grants [LightNotificationListenerService] notification listener access and asks the system to
     * bind it, so tools' alerts can bring them forward and [onActiveNotificationsChanged] is
     * called. Needs MANAGE_NOTIFICATION_LISTENERS, which the system uid has.
     */
    fun Context.enableNotificationListener() {
        LightNotificationListenerService.ensureEnabled(this)
    }

    /**
     * A receiver that will bring the rootActivity (MainActivity in both emulator and real LightOS)
     * to the foreground with a new intent whenever the device's screen goes off (unless user has
     * overridden the settings.forceFocusLevel). If a tool has an alert the user hasn't cleared,
     * that tool is brought forward instead, so the alert is what shows when the screen turns back
     * on (unless forceFocusLevel is Never).
     */
    fun Context.registerLockReceiver(
        rootActivityClass: Class<out Activity>,
        settings: LightSdkServerSettings,
    ): BroadcastReceiver {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        val lockReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val alertingTool = LightNotificationListenerService.latestAlertingTool()
                    if (
                        alertingTool != null &&
                            settings.forceFocusLevel != ForceFocusLevel.Never &&
                            isPackageAllowed(settings.clientFilterLevel, context, alertingTool)
                    ) {
                        LightNotificationListenerService.resurface(context, alertingTool)
                        return
                    }
                    val allowForceFocus =
                        when (settings.forceFocusLevel) {
                            ForceFocusLevel.Always -> true
                            ForceFocusLevel.AlertsOnly,
                            ForceFocusLevel.Never -> false
                        }
                    if (allowForceFocus && intent.action == Intent.ACTION_SCREEN_OFF) {
                        val i =
                            Intent(context, rootActivityClass)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra(SCREEN_OFF_FLAG, true)
                        context.startActivity(i)
                    }
                }
            }
        registerReceiver(lockReceiver, filter)
        return lockReceiver
    }

    /**
     * Snapshot of an APK's identity for trust evaluation. [isInstalled] distinguishes an
     * already-installed package from an archive being evaluated ahead of install (e.g. before
     * allowing a sideload); [apkPaths]/[lastUpdateTime]/[firstInstallTime] are only meaningful for
     * the installed case, since an archive has no installation history yet.
     */
    data class ApkMetadata(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val signingCertSha256: List<String>,
        val apkPaths: List<String> = emptyList(),
        val lastUpdateTime: Long = 0,
        val isInstalled: Boolean = true,
        val firstInstallTime: Long = 0,
    )

    /** Builds [ApkMetadata] from an installed package's [PackageInfo]. */
    fun readApkMetadata(info: PackageInfo): ApkMetadata {
        val signingInfo = info.signingInfo
        val signatures: Array<Signature> =
            if (signingInfo != null) {
                signingInfo.apkContentsSigners
            } else emptyArray()

        val sha256Hashes = signatures.map { it.toByteArray().sha256Hex() }

        return ApkMetadata(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            signingCertSha256 = sha256Hashes,
            apkPaths =
                info.applicationInfo
                    ?.let { listOf(it.sourceDir) + it.splitSourceDirs.orEmpty() }
                    .orEmpty(),
            lastUpdateTime = info.lastUpdateTime,
            firstInstallTime = info.firstInstallTime,
        )
    }

    /** Builds [ApkMetadata] for a not-yet-installed APK file, e.g. ahead of allowing a sideload. */
    fun Context.readApkMetadata(apkFile: File): ApkMetadata? {
        val pm = packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES

        val info: PackageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return null

        // Required so the ApplicationInfo resolves correctly (icons, etc.) —
        // not strictly needed just for package name/signatures, but harmless to set.
        info.applicationInfo?.sourceDir = apkFile.absolutePath
        info.applicationInfo?.publicSourceDir = apkFile.absolutePath
        return readApkMetadata(info).copy(isInstalled = false)
    }
}
