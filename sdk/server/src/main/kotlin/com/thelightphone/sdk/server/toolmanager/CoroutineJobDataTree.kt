package com.thelightphone.sdk.server.toolmanager

import com.thelightphone.toolmanager.datatree.DeferredJobTracker
import com.thelightphone.toolmanager.datatree.JobDataTree
import com.thelightphone.toolmanager.datatree.JobResult
import com.thelightphone.toolmanager.datatree.JobStart
import com.thelightphone.toolmanager.datatree.JobStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

// A JobDataTree whose job is a single suspend operation awaited in the background (a websocket
// tunnel, a long-poll, etc)
abstract class CoroutineJobDataTree<T>(
    private val jobScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : JobDataTree() {
    private val jobTracker = DeferredJobTracker(jobScope)

    // Tracks the single in-flight job per path, so starting a new job at a path that already has
    // one running supersedes (rather than races) it.
    private val activeJobs = ConcurrentHashMap<Path, Pair<String, Job>>()

    override suspend fun getBytes(filePath: Path): Result<InputStream> =
        Result.failure(NoSuchElementException("no such job result: $filePath"))

    override suspend fun startJob(
        path: Path,
        params: Map<String, String>,
        selfOrigin: String,
        mintCallbackState: (jobId: String) -> String
    ): Result<JobStart> {
        val jobId = jobTracker.startPending()
        val redirectUrl = prepareRedirectUrl(jobId, path, params)
        val job = jobScope.launch {
            processResult(path, jobId, runJob(jobId))
        }
        activeJobs.put(path, jobId to job)?.let { (previousJobId, previousJob) ->
            previousJob.cancel()
            jobTracker.complete(
                previousJobId,
                Result.failure(CancellationException("superseded by a newer job at $path")),
            )
        }
        return Result.success(JobStart(jobId, redirectUrl))
    }

    // Any synchronous setup startJob needs before it can return - most commonly a URL to send the
    // caller to.
    protected open fun prepareRedirectUrl(jobId: String, path: Path, params: Map<String, String>): String? = null

    // Does the actual work this job represents.
    protected abstract suspend fun runJob(jobId: String): T

    // Validates/consumes runJob's result
    protected abstract suspend fun consumeResult(result: T): Result<JobResult>

    private suspend fun processResult(path: Path, jobId: String, result: T) {
        // Only clear the path's active-job slot if it's still this job
        activeJobs.compute(path) { _, current -> if (current?.first == jobId) null else current }

        // The job may have already been superseded (and marked failed) by a newer startJob() call
        if (jobTracker.status(jobId) !is JobStatus.Running) return

        consumeResult(result).fold(
            onSuccess = { jobResult -> jobTracker.complete(jobId, Result.success(jobResult)) },
            onFailure = { error -> jobTracker.complete(jobId, Result.failure(error)) },
        )
    }

    override suspend fun getJobStatus(path: Path, jobId: String): JobStatus =
        jobTracker.status(jobId)
}
