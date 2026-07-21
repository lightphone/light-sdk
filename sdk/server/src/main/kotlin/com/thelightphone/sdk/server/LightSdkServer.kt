package com.thelightphone.sdk.server

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
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

    fun List<InstalledClient>.filterAllowedTools(context: Context): List<InstalledClient> {
        val clientFilterLevel = LightSdkServerSettings(context).clientFilterLevel
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

    fun Context.queryEnabledClients(): List<InstalledClient> {
        return queryInstalledClients()
            .filter { isSdkVersionSupported(it.sdkVersion) }
            .filterAllowedTools(this)
    }

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
        setOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        ).contains(permissionName)
    }

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
}
