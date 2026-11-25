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
import com.thelightphone.sdk.server.ToolPurgeOutcome
import com.thelightphone.sdk.trust.BlockAction
import com.thelightphone.sdk.trust.LightInstallDecision
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class LightToolPurgeWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val packageName = inputData.getString(PACKAGE) ?: return Result.failure()
        val attemptId = id.toString()
        fun failed(reason: String): Result {
            LightSdkServer.toolEnforcement?.onPurgeOutcome(packageName, attemptId, ToolPurgeOutcome.Failed(reason))
            return Result.failure()
        }

        return try {
            val info = applicationContext.packageManager.getApplicationInfo(packageName, 0)
            if (packageName == applicationContext.packageName || info.uid == applicationContext.applicationInfo.uid || info.uid == Process.SYSTEM_UID)
                return failed("Package is protected from removal")
            if (applicationContext.queryInstalledClients().none { it.packageInfo.packageName == packageName })
                return failed("Package is no longer an installed SDK tool")
            val evaluation = LightSdkServer.evaluateTool(LightSdkServer.provideSdkSettings(applicationContext).clientFilterLevel,
                applicationContext, packageName)
            // A non-null Kill here can only come from an un-bypassed PolicyDecision - see the
            // matching comment in ToolEnforcementCoordinator.
            val kill = evaluation.decision as? LightInstallDecision.Kill
            if (kill?.action != BlockAction.Purge || evaluation.bundleVersion == null) {
                LightSdkServer.toolEnforcement?.onPurgeOutcome(packageName, attemptId, ToolPurgeOutcome.Skipped)
                return Result.success()
            }
            val callback = Intent(applicationContext, LightToolPurgeResultReceiver::class.java)
                .setData(android.net.Uri.parse("light-tool-purge:$packageName/$attemptId"))
                .putExtra(PACKAGE, packageName)
                .putExtra(ATTEMPT, attemptId)
            val pending = PendingIntent.getBroadcast(applicationContext, 0, callback,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            applicationContext.packageManager.packageInstaller.uninstall(packageName, pending.intentSender)
            Result.success()
        } catch (error: Exception) {
            LightSdkServer.reportError("ToolEnforcement", error, "Purge request failed for $packageName")
            failed("Uninstall request failed")
        }
    }

    companion object {
        const val PACKAGE = "purge_package"
        const val ATTEMPT = "purge_attempt"

        internal fun enqueue(context: Context, packageName: String): String = runBlocking {
            val workManager = WorkManager.getInstance(context)
            val name = "tool_policy_purge_$packageName"
            workManager.getWorkInfosForUniqueWorkFlow(name).first()
                .firstOrNull { !it.state.isFinished }?.let { return@runBlocking it.id.toString() }
            val request = OneTimeWorkRequestBuilder<LightToolPurgeWorker>()
                .setInputData(workDataOf(PACKAGE to packageName)).build()
            workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).await()
            request.id.toString()
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
            intent.getStringExtra(LightToolPurgeWorker.ATTEMPT)?.let { attemptId ->
                LightSdkServer.toolEnforcement?.onPurgeOutcome(
                    packageName, attemptId, ToolPurgeOutcome.Failed("Uninstall failed: status=$status"),
                )
            }
            LightSdkServer.reportError("ToolEnforcement", null,
                "Purge not completed for $packageName: status=$status")
        }
    }
}
