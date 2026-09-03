package com.thelightphone.sdk

import com.thelightphone.toolmanager.JobStartResponse
import com.thelightphone.toolmanager.JobState
import com.thelightphone.toolmanager.JobStatusResponse
import com.thelightphone.toolmanager.LightFileProviderJobs
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

class LightToolManagerJobBridge(
    private val sealedContext: SealedLightContext
) : LightFileProviderJobs {
    private val remoteJobStatusMap = ConcurrentHashMap<String, JobState>()

    override fun startJob(
        path: String,
        jobId: String,
        params: Map<String, String>,
        callbackUrl: String?
    ): JobStartResponse? {
        if (LightSdkRegistry.jobs.containsKey(path)) {
            LightWork.enqueue(sealedContext, jobKey = path, inputData = params)
            return JobStartResponse(jobId)
        }

        LightSdkRegistry.remoteJobs[path]?.let {
            val redirect = it.getRedirectUrl(sealedContext, callbackUrl, params)
            remoteJobStatusMap[jobId] = JobState.RUNNING
            return JobStartResponse(jobId, redirect)
        }
        return null
    }

    override fun getJobStatus(
        path: String,
        jobId: String
    ): JobStatusResponse? {
        // check remote jobs first
        remoteJobStatusMap[jobId]?.let {
            return JobStatusResponse(jobId, it)
        }

        // then check for actual running jobs
        val state = runBlocking {
            LightWork.getState(sealedContext, jobId)
        }
        return when(state) {
            LightJobState.Cancelled -> JobStatusResponse(jobId, JobState.FAILED, message = "This operation was cancelled.")
            LightJobState.Enqueued -> JobStatusResponse(jobId, JobState.PENDING)
            is LightJobState.Failed -> {
                val reason = state.outputData[LIGHT_FAIL_REASON]
                JobStatusResponse(jobId, JobState.FAILED, message = reason ?: "Job failed for unknown reason.")
            }
            LightJobState.NotScheduled -> JobStatusResponse(jobId, JobState.FAILED, message = "Error: unknown job.")
            LightJobState.Running -> JobStatusResponse(jobId, JobState.RUNNING)
            is LightJobState.Succeeded -> {
                val resultPath = state.outputData[LIGHT_SUCCESS_OUTPUT_FILE]
                JobStatusResponse(jobId, JobState.SUCCEEDED, resultPath = resultPath)
            }
        }
    }

    override fun completeJob(
        path: String,
        jobId: String,
        data: Map<String, String>
    ): Boolean {
        if (!remoteJobStatusMap.containsKey(jobId)) {
            return false
        }
        val success = LightSdkRegistry.remoteJobs[path]?.onComplete(sealedContext, jobId, data) ?: false
        remoteJobStatusMap[jobId] = if (success) JobState.SUCCEEDED else JobState.FAILED
        return success
    }
}