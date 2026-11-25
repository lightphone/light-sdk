package com.thelightphone.sdk.server

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.thelightphone.sdk.server.installer.LightToolPurgeWorker
import com.thelightphone.sdk.trust.InstallationFingerprint

class AndroidToolRestrictionPlatform(context: Context) : ToolRestrictionPlatform {
    private val context = context.applicationContext

    private fun requireExternalTool(packageName: String) {
        val info = context.packageManager.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        require(packageName != context.packageName && info.uid != context.applicationInfo.uid && info.uid != Process.SYSTEM_UID) {
            "Refusing to restrict the host or a core shared UID"
        }
    }

    override fun state(packageName: String): ToolPackageState {
        requireExternalTool(packageName)
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val app = requireNotNull(info.applicationInfo)
        require(app.splitSourceDirs.isNullOrEmpty()) { "Split APK binding is unsupported" }
        return ToolPackageState(context.packageManager.getApplicationEnabledSetting(packageName),
            InstallationFingerprint(app.sourceDir, info.firstInstallTime, info.lastUpdateTime))
    }

    override fun setEnabledState(packageName: String, enabledState: Int) {
        requireExternalTool(packageName)
        // Flags 0 retain Android's normal process-kill behavior when disabling.
        context.packageManager.setApplicationEnabledSetting(packageName, enabledState, 0)
    }

    override fun stopProcesses(packageName: String) {
        requireExternalTool(packageName)
        val manager = context.getSystemService(ActivityManager::class.java)
        try {
            ActivityManager::class.java.getMethod("forceStopPackage", String::class.java).invoke(manager, packageName)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException("Platform refused force-stop", error.targetException)
        }
    }

    override fun requestPurge(packageName: String): String {
        requireExternalTool(packageName)
        return LightToolPurgeWorker.enqueue(context, packageName)
    }
}
