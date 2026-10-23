package com.thelightphone.sdk.server.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.thelightphone.sdk.server.LightSdkServer

class LightApkUninstallWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {

    companion object {
        private const val TAG = "LightApkUninstallWorker"
        const val KEY_PACKAGE_NAME = "package_name"

        fun enqueue(context: Context, packageName: String) {
            val data = Data.Builder()
                .putString(KEY_PACKAGE_NAME, packageName)
                .build()
            val request = OneTimeWorkRequest.Builder(LightApkUninstallWorker::class.java)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "apk_uninstall_$packageName",
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }
    }

    override fun doWork(): Result {
        val packageName = inputData.getString(KEY_PACKAGE_NAME)
            ?: return Result.failure()

        // Re-check here: the work may have been enqueued a while ago, and this is the last
        // line of defense before actually removing a package.
        if (packageName == applicationContext.packageName ||
            !LightSdkServer.canBeUninstalledByToolManager(packageName)
        ) {
            Log.e(TAG, "Refusing to uninstall package: $packageName")
            return Result.failure()
        }

        return try {
            uninstallPackage(packageName)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to uninstall package: $packageName", e)
            Result.failure()
        }
    }

    private fun uninstallPackage(packageName: String) {
        val intent = Intent(
            applicationContext,
            LightApkUninstallResultReceiver::class.java
        )
        intent.putExtra(KEY_PACKAGE_NAME, packageName)
        val pi = PendingIntent.getBroadcast(
            applicationContext,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        applicationContext.packageManager.packageInstaller.uninstall(packageName, pi.intentSender)
        Log.d(TAG, "Uninstall requested for $packageName")
    }
}
