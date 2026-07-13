package com.thelightphone.sdk.server

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.util.Log
import com.thelightphone.sdk.shared.LightConstants
import com.thelightphone.sdk.shared.LightResult


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
    fun isSdkVersionSupported(sdkVersion: String): Boolean {
        return true
    }

    fun List<InstalledClient>.filterAllowedTools(settings: LightSdkServerSettings): List<InstalledClient> {
        val clientFilterLevel = settings.clientFilterLevel
        return filter { isPackageAllowed(clientFilterLevel, it.packageInfo.packageName) }
    }

    fun isPackageAllowed(
        clientFilterLevel: ClientFilterLevel,
        packageName: String
    ): Boolean {
        return when (clientFilterLevel) {
            ClientFilterLevel.ExcludeAllApks -> false
            ClientFilterLevel.AllowAllApks -> true
            ClientFilterLevel.AllowLightApprovedApks -> {
                checkCert(packageName) == ClientCertType.LightSdkApproved
            }

            ClientFilterLevel.AllowLightSignedApks -> when (checkCert(packageName)) {
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
                    packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
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
            .filterAllowedTools(settings)
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
     * Given an apk's package name, determine if it's been built with the Light SDK and/or promoted by Light
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var checkCert: (callingPackage: String) -> ClientCertType = { ClientCertType.Unknown }

    /**
     * Given an android permission id (android.manifest.CAMERA, for example), return whether
     * this server instance is allowed to grant that permission to the calling package
     *
     * Settable from enclosing application!! May be run on any thread
     */
    var androidPermissionAllowed: (callingUid: Int, permissionName: String) -> Boolean = { _, permissionName ->
        // default grantable permissions; enclosing app may override
        setOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_MEDIA_AUDIO,
        ).contains(permissionName)
    }

    var permissionActivity: Class<out Activity>? = null


    var grantPermission: (context: Context, packageName: String, permission: String) -> Result<Unit> = { context, packageName, permission ->
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
    fun Context.registerLockReceiver(rootActivityClass: Class<out Activity>, settings: LightSdkServerSettings) : BroadcastReceiver {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        val lockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val allowForceFocus = when(settings.forceFocusLevel) {
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
}