package com.thelightphone.sdk

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class LightToolManagerNotifyJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        LightSdkRegistry.entryPoint?.onToolManagerDataUpdate()
        return Result.success()
    }
}

internal fun Context.enqueueLightManagerNotifyJob() {
    val request = OneTimeWorkRequestBuilder<LightToolManagerNotifyJob>().build()
    WorkManager.getInstance(this).enqueueUniqueWork(
        "LightToolManagerNotifyJob",
        ExistingWorkPolicy.KEEP,
        request,
    )
}