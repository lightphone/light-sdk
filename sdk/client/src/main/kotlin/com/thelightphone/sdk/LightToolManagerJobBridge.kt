package com.thelightphone.sdk

import com.thelightphone.toolmanager.JobStartResponse
import com.thelightphone.toolmanager.JobState
import com.thelightphone.toolmanager.JobStatusResponse
import com.thelightphone.toolmanager.LightFileProviderJobs
import kotlinx.coroutines.runBlocking

// Wraps Tool Manager's concept of a Job with SDKs LightJob
class LightToolManagerJobBridge(
    private val sealedContext: SealedLightContext
) : LightFileProviderJobs {

    override fun startJob(
        path: String,
        jobId: String,
        params: Map<String, String>,
        callbackUrl: String?
    ): JobStartResponse? {
        if (!LightSdkRegistry.jobs.containsKey(path)) return null
        LightWork.enqueue(sealedContext, jobKey = path, inputData = params, tag = jobId)
        return JobStartResponse(jobId)
    }

    override fun getJobStatus(
        path: String,
        jobId: String
    ): JobStatusResponse {
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
                val message = state.outputData[LIGHT_SUCCESS_MESSAGE]
                JobStatusResponse(jobId, JobState.SUCCEEDED, resultPath = resultPath, message = message)
            }
        }
    }

    // Local (@LightJob) jobs complete on their own via WorkManager, not through an
    // out-of-band callback, so there's nothing for this bridge to accept here.
    override fun completeJob(
        path: String,
        jobId: String,
        data: Map<String, String>
    ): Boolean = false
}