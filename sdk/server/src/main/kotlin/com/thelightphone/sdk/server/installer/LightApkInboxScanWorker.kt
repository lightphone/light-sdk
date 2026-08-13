package com.thelightphone.sdk.server.installer

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.thelightphone.sdk.server.LightSdkServer

class LightApkInboxScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "LightApkInboxScanWorker"
        const val WORK_NAME = "apk_inbox_scan"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequest.Builder(LightApkInboxScanWorker::class.java).build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }

    override fun doWork(): Result {
        val inboxDir = LightSdkServer.getApkInboxDirectory(applicationContext)
        if (!inboxDir.exists() || !inboxDir.isDirectory) {
            Log.d(TAG, "APK inbox directory does not exist")
            return Result.success()
        }

        val apkFiles = inboxDir.listFiles { file -> file.extension.equals("apk", ignoreCase = true) }
        if (apkFiles.isNullOrEmpty()) {
            Log.d(TAG, "No APKs found in inbox")
            return Result.success()
        }

        Log.d(TAG, "Found ${apkFiles.size} APK(s) in inbox")
        for (apk in apkFiles) {
            val data = Data.Builder()
                .putString(LightApkInstallWorker.KEY_APK_PATH, apk.absolutePath)
                .build()

            val installRequest = OneTimeWorkRequest.Builder(LightApkInstallWorker::class.java)
                .setInputData(data)
                .build()

            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(
                    "apk_install_${apk.name}",
                    ExistingWorkPolicy.KEEP,
                    installRequest
                )
        }

        return Result.success()
    }
}
