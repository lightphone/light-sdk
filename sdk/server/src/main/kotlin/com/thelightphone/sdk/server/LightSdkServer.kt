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
import android.util.Log
import com.thelightphone.sdk.server.toolmanager.getApkInboxAuthDirectory
import com.thelightphone.toolmanager.AndroidKeystoreKeyCipher
import com.thelightphone.toolmanager.BranchView
import com.thelightphone.toolmanager.DataView
import com.thelightphone.toolmanager.KeyCipher
import com.thelightphone.toolmanager.RootViewSpec
import com.thelightphone.toolmanager.datatree.BranchDataTree
import com.thelightphone.toolmanager.datatree.RootDataTree
import com.thelightphone.sdk.shared.LightConstants
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.toolmanager.LocalNetworkInfo
import com.thelightphone.toolmanager.ToolManagerAuth
import com.thelightphone.toolmanager.TotpToolManagerAuth
import java.io.File
import java.security.MessageDigest


data class InstalledClient(
    val packageInfo: PackageInfo,
    val sdkVersion: String,
)

enum class ClientCertType {
    Unknown,

    // for tools built with SDK and signed by Light, but not part of curated community list
    LightSdkSignedUnverified,

    // for tools built with SDK, signed by Light, and part of community list
    LightSdkApproved
}

object LightSdkServer {
    private const val TAG = "LightSdkServer"
    const val SCREEN_OFF_FLAG = "SCREEN_OFF"

    val Context.runningAsSystemApp: Boolean
        get() {
            val isSystemUid = (Process.myUid() == Process.SYSTEM_UID)
            val isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            return isSystemApp && isSystemUid
        }

    /**
     * returns true iff this server version supports tools built with given sdkVersion
     */
    var isSdkVersionSupported: (sdkVersion: String) -> Boolean = { true }

    fun List<InstalledClient>.filterAllowedInstalledTools(
        settings: LightSdkServerSettings
    ): List<InstalledClient> {
        val clientFilterLevel = settings.clientFilterLevel
        return filter {
            val meta = readApkMetadata(it.packageInfo)
            isPackageAllowed(clientFilterLevel, meta)
        }
    }

    fun isPackageAllowed(
        clientFilterLevel: ClientFilterLevel,
        packageMeta: ApkMetadata
    ): Boolean {
        return isPackageAllowed(clientFilterLevel) { checkCert(packageMeta) }
    }

    fun isPackageAllowed(
        clientFilterLevel: ClientFilterLevel,
        context: Context,
        callingPackage: String
    ): Boolean {
        return isPackageAllowed(clientFilterLevel) { checkCert(context, callingPackage) }
    }

    fun isPackageInstallable(
        clientFilterLevel: ClientFilterLevel,
        context: Context,
        apkFile: File
    ): Boolean {
        return isPackageAllowed(clientFilterLevel) { checkCertUninstalled(context, apkFile) }
    }

    inline fun isPackageAllowed(
        clientFilterLevel: ClientFilterLevel,
        checkCert: () -> ClientCertType
    ): Boolean {
        return when (clientFilterLevel) {
            ClientFilterLevel.ExcludeAllApks -> false
            ClientFilterLevel.AllowAllApks -> true
            ClientFilterLevel.AllowLightApprovedApks -> {
                checkCert() == ClientCertType.LightSdkApproved
            }

            ClientFilterLevel.AllowLightSignedApks -> when (checkCert()) {
                ClientCertType.Unknown -> false
                ClientCertType.LightSdkSignedUnverified, ClientCertType.LightSdkApproved -> true
            }
        }
    }

    fun Context.queryInstalledClients(): List<InstalledClient> {
        val marker = Intent(LightConstants.ACTION_SDK_MARKER)

        val results = packageManager.queryBroadcastReceivers(
            marker,
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
        )

        return results.map {
            val packageName = it.activityInfo.packageName
            val packageInfo: PackageInfo
            try {
                packageInfo =
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
                    )
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Could not find SDK package", e)
                return@map null
            }
            val meta = it.activityInfo.metaData ?: run {
                Log.e(TAG, "SDK client didn't provide metadata: $packageName")
                return@map null
            }
            val sdkVersion = meta.getString(LightConstants.SDK_VERSION_KEY) ?: run {
                Log.e(TAG, "SDK client didn't provide sdkVersion: $packageName")
                return@map null
            }

            InstalledClient(packageInfo, sdkVersion)
        }.filterNotNull()
    }

    fun Context.queryEnabledClients(settings: LightSdkServerSettings): List<InstalledClient> {
        return queryInstalledClients()
            .filter { isSdkVersionSupported(it.sdkVersion) }
            .filterAllowedInstalledTools(settings)
    }

    var getApkInboxDirectory: (Context) -> File = {
        File(it.applicationContext.filesDir, "apkInbox")
    }

    var getToolManagerKeyCipher: () -> KeyCipher = {
        AndroidKeystoreKeyCipher()
    }

    var provideSdkSettings: (Context) -> LightSdkServerSettings = {
        DefaultLightSdkServerSettings(it)
    }

    var defaultClientFilterLevel: ClientFilterLevel = ClientFilterLevel.AllowLightApprovedApks

    /**
     * defines the behavior for when the server app should "foreground" itself over other running apps
     * LightOS is aggressive about this by default. It will foreground itself for all alerts, and whenever
     * the phone is locked.
     */
    var defaultForceFocusLevel: ForceFocusLevel = ForceFocusLevel.Always

    /**
     * return the POST endpoint that the calling tool's application server should use to
     * get UnifiedPush through Light's server, down to LightOS/emulator, then over to Tool
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
    var customServiceMethodResolver: (callingId: Int, methodId: String, payload: String?) -> LightResult<String> =
        { callingId, methodId, payload ->
            Log.e(TAG, "Service method $methodId not found!")
            LightResult.Error(LightResult.ErrorCode.Unknown, "unknown method: $methodId")
        }

    /**
     * Handle a hardware key event forwarded from a client whose current screen did not consume
     * it.
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var onDeviceKeyEvent: (callingUid: Int, event: LightServiceMethod.DeviceKeyEvent.Request) -> Unit =
        { _, event ->
            Log.w(TAG, "Unhandled device key event: $event")
        }

    /**
     * Open the phone dialer for [phoneNumber]
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var onOpenDialer: (callingUid: Int, phoneNumber: String) -> Unit =
        { _, phoneNumber ->
            Log.e(TAG, "OpenDialer not configured by server: phoneNumber=$phoneNumber")
        }

    var foregroundSelfWithCallback: (componentToReturnTo: ComponentName) -> Unit = {
        Log.e(TAG, "Server wants to foreground itself but does not know how!")
    }

    var isLightSigned: (callingPackage: String, signatureHash: String) -> Boolean =
        { _, _ -> false }
    var isLightApproved: (callingPackage: String, signatureHash: String) -> Boolean =
        { _, _ -> false }

    /**
     * Given an android permission id (android.manifest.CAMERA, for example), return whether
     * this server instance is allowed to grant that permission to the calling package
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var androidPermissionAllowed: (callingUid: Int, permissionName: String) -> Boolean =
        { _, permissionName ->
            // default grantable permissions; enclosing app may override
            setOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.RECORD_AUDIO,
            ).contains(permissionName)
        }

    /**
     * Called when the SDK server successfully installs a tool
     * May be called with a null path if unknown apk was installed
     * (or we want to notify the server that it should refresh it's apk list)
     */
    var onApkInstalled: (apkPath: String?) -> Unit = { apkPath ->
        if (!apkPath.isNullOrBlank()) {
            Log.d(TAG, "Successfully installed APK: $apkPath")
        }
    }

    /**
     * SDK server should have a singleTask Activity to represent the ToolManager's UI
     * If the ToolManager is running and the server app is active, the ToolManager UI should always show
     */
    var foregroundToolManagerUi: (activityToReplace: Activity) -> Unit = {
        // if activityToReplace is not the ToolManager UI already, relaunch the ToolManager UI
    }

    var isNetworkApprovedForToolManager: (LocalNetworkInfo) -> Boolean = {
        // by default don't trust any network. This MUST be overridden
        false
    }

    var reportError: (tag: String, exception: Throwable?, message: String) -> Unit = { tag, e, msg ->
        Log.e(tag, msg, e)
    }

    var verboseLoggingEnabled = true

    var rootToolManagerDataProvider: () -> RootDataTree = {
        RootDataTree {
            val stubDataTree = object : BranchDataTree {
                override suspend fun getChildren(): List<DataView<*>> = emptyList()
            }
            BranchView(RootViewSpec("Empty Root", "emptyRoot"), stubDataTree, forceHide = true)
        }
    }

    /**
     * Activity to launch when allowing user to grant permission via server app
     */
    var permissionActivity: Class<out Activity>? = null

    var grantPermission: (context: Context, packageName: String, permission: String) -> Result<Unit> =
        { context, packageName, permission ->
            runCatching {
                // Fine for emulator, can avoid reflection on real system apps
                val grant = PackageManager::class.java.getMethod(
                    "grantRuntimePermission",
                    String::class.java,
                    String::class.java,
                    UserHandle::class.java
                )
                grant.invoke(
                    context.packageManager,
                    packageName,
                    permission,
                    Process.myUserHandle()
                )
            }
        }

    /**
     * A receiver that will bring the rootActivity (MainActivity in both emulator and real LightOS)
     * to the foreground with a new intent whenever the device's screen goes off
     * (unless user has overridden the settings.forceFocusLevel)
     */
    fun Context.registerLockReceiver(
        rootActivityClass: Class<out Activity>,
        settings: LightSdkServerSettings
    ): BroadcastReceiver {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        val lockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val allowForceFocus = when (settings.forceFocusLevel) {
                    ForceFocusLevel.Always -> true
                    ForceFocusLevel.AlertsOnly, ForceFocusLevel.Never -> false
                }
                if (allowForceFocus && intent.action == Intent.ACTION_SCREEN_OFF) {
                    val i = Intent(context, rootActivityClass)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(SCREEN_OFF_FLAG, true)
                    context.startActivity(i)
                }
            }
        }
        registerReceiver(lockReceiver, filter)
        return lockReceiver
    }

    data class ApkMetadata(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val signingCertSha256: List<String>
    )

    fun readApkMetadata(info: PackageInfo): ApkMetadata {
        val signingInfo = info.signingInfo
        val signatures: Array<Signature> = if (signingInfo != null) {
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else emptyArray()

        val sha256Hashes = signatures.map { sig ->
            MessageDigest.getInstance("SHA-256")
                .digest(sig.toByteArray())
                .toHexString()
        }

        return ApkMetadata(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            signingCertSha256 = sha256Hashes
        )
    }

    fun Context.readApkMetadata(apkFile: File): ApkMetadata? {
        val pm = packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES

        val info: PackageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return null

        // Required so the ApplicationInfo resolves correctly (icons, etc.) —
        // not strictly needed just for package name/signatures, but harmless to set.
        info.applicationInfo?.sourceDir = apkFile.absolutePath
        info.applicationInfo?.publicSourceDir = apkFile.absolutePath
        return readApkMetadata(info)
    }

    fun checkCertUninstalled(context: Context, apkFile: File): ClientCertType {
        val packageMeta = context.readApkMetadata(apkFile) ?: return ClientCertType.Unknown
        return checkCert(packageMeta)
    }

    fun checkCert(context: Context, callingPackage: String): ClientCertType {
        val info = try {
            context.packageManager.getPackageInfo(
                callingPackage,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return ClientCertType.Unknown
        }
        val packageMeta = readApkMetadata(info)
        return checkCert(packageMeta)
    }

    fun checkCert(packageMeta: ApkMetadata): ClientCertType {
        val lightSignedMatches = packageMeta.signingCertSha256.filter {
            isLightSigned(packageMeta.packageName, it)
        }

        if (lightSignedMatches.isEmpty()) {
            return ClientCertType.Unknown
        }

        val lightApprovedMatches = lightSignedMatches.filter {
            isLightApproved(packageMeta.packageName, it)
        }

        return if (lightApprovedMatches.isEmpty()) ClientCertType.LightSdkSignedUnverified else ClientCertType.LightSdkApproved
    }
}
