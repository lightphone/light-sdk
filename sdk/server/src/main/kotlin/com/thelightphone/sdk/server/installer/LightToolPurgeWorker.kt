package com.thelightphone.sdk.server.installer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Process
import androidx.work.*
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.server.LightSdkServer.queryInstalledClients
import com.thelightphone.sdk.trust.BlockAction
import com.thelightphone.sdk.trust.LightInstallDecision

class LightToolPurgeWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val packageName = inputData.getString(PACKAGE) ?: return Result.failure()
        return try {
            val info = applicationContext.packageManager.getApplicationInfo(packageName, 0)
            if (packageName == applicationContext.packageName || info.uid == applicationContext.applicationInfo.uid || info.uid == Process.SYSTEM_UID)
                return Result.failure()
            if (applicationContext.queryInstalledClients().none { it.packageInfo.packageName == packageName }) return Result.failure()
            val evaluation = LightSdkServer.evaluateTool(LightSdkServer.provideSdkSettings(applicationContext).clientFilterLevel,
                applicationContext, packageName)
            // A non-null Kill here can only come from an un-bypassed PolicyDecision - see the
            // matching comment in ToolEnforcementCoordinator.
            val kill = evaluation.decision as? LightInstallDecision.Kill
            if (kill?.action != BlockAction.Purge || evaluation.bundleVersion == null) return Result.success()
            val callback = Intent(applicationContext, LightToolPurgeResultReceiver::class.java)
                .setData(android.net.Uri.parse("light-tool-purge:$packageName/${java.util.UUID.randomUUID()}"))
                .putExtra(PACKAGE, packageName)
                .putExtra(VERSION, requireNotNull(evaluation.bundleVersion))
            val pending = PendingIntent.getBroadcast(applicationContext, 0, callback,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            applicationContext.packageManager.packageInstaller.uninstall(packageName, pending.intentSender)
            Result.success()
        } catch (error: Exception) {
            LightSdkServer.reportError("ToolEnforcement", error, "Purge request failed for $packageName")
            Result.failure()
        }
    }

    companion object {
        const val PACKAGE = "purge_package"
        const val VERSION = "purge_bundle_version"
        fun enqueue(context: Context, packageName: String) {
            val request = OneTimeWorkRequestBuilder<LightToolPurgeWorker>()
                .setInputData(workDataOf(PACKAGE to packageName)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("tool_policy_purge_$packageName", ExistingWorkPolicy.KEEP, request)
        }
    }
}

class LightToolPurgeResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(LightToolPurgeWorker.PACKAGE) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            LightSdkServer.toolTrustRuntime?.onPackageRemoved(packageName, false)
            LightSdkServer.toolEnforcement?.onPackageRemoved(packageName, false)
            LightSdkServer.onApkUninstalled(packageName)
        } else {
            LightSdkServer.toolEnforcement?.onPurgeFailed(packageName, intent.getLongExtra(LightToolPurgeWorker.VERSION, -1), status)
            LightSdkServer.reportError("ToolEnforcement", null,
                "Purge not completed for $packageName: status=$status")
        }
    }
}
