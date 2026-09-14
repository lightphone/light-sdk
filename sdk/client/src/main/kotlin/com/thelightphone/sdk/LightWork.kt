package com.thelightphone.sdk

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Marks a top-level `val` of type [LightJobHandler] as a background job, registered
 * under [key] for the rest of the app.
 *
 * Pass the same [key] string to [LightWork.enqueue], [LightWork.enqueuePeriodic], or
 * [LightWork.cancel] to schedule or cancel the job. Keys must be non-empty and unique
 * across the app.
 *
 * Example:
 * ```
 * @LightJob("sync-contacts")
 * val syncContacts: LightJobHandler = { ctx, input ->
 *     // …do work…
 *     LightJobResult.Success()
 * }
 *
 * // elsewhere:
 * LightWork.enqueue(lightContext, "sync-contacts")
 * ```
 *
 * @property key the stable identifier used to look up this job at enqueue time.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class LightJob(val key: String)

/**
 * Marks a top-level `object` implementing [LightRemoteJobHandler] as a remote job,
 * registered under [key] for the rest of the app. The object must be callable without
 * instantiation, so KSP requires it be a Kotlin `object` rather than a class.
 *
 * @property key the stable identifier used to look up this job at dispatch time.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class LightRemoteJob(val key: String)

const val LIGHT_FAIL_REASON = "LIGHT_FAIL_REASON"
const val LIGHT_SUCCESS_MESSAGE = "LIGHT_SUCCESS_MESSAGE"
const val LIGHT_SUCCESS_OUTPUT_FILE = "LIGHT_SUCCESS_OUTPUT_FILE"

sealed interface LightJobResult {
    /** Job finished successfully. */
    class Success(val outputData: Map<String, String> = emptyMap(), val outputFilePath: String?, val message: String?) : LightJobResult

    /** Job hit a transient failure. Will automatically reschedule with backoff. */
    object Retry : LightJobResult

    /** Job has failed permanently. */
    class Error(val reason: String, val outputData: Map<String, String> = emptyMap()) : LightJobResult
}

typealias LightJobHandler = suspend (SealedLightContext, Map<String, String>) -> LightJobResult

interface LightRemoteJobHandler {
    fun getRedirectUrl(context: SealedLightContext, callbackUrl: String?, inputParams: Map<String, String>): String? = null
    fun onComplete(context: SealedLightContext, jobId: String, output: Map<String, String>): Boolean = false
}

/**
 * Current state of a scheduled [LightJob] instance.
 *
 * Use [LightWork.observe] to watch state changes over time, or [LightWork.getState] for
 * a one-shot read. [Succeeded], [Failed], and [Cancelled] are terminal for a one-time
 * job; periodic jobs cycle from [Succeeded]/[Failed] back through [Enqueued] each interval.
 */
sealed interface LightJobState {
    /** Scheduled and waiting to run (or chained behind another job). */
    object Enqueued : LightJobState

    /** Currently executing on a WorkManager thread. */
    object Running : LightJobState

    /** Finished successfully. [outputData] is whatever the handler returned via [LightJobResult.Success]. */
    class Succeeded(val outputData: Map<String, String>) : LightJobState

    /** Finished with a [LightJobResult.Error] or exhausted retries. */
    class Failed(val outputData: Map<String, String>) : LightJobState

    /** Cancelled before completion via [LightWork.cancel] or by being replaced. */
    object Cancelled : LightJobState

    /** No work is currently registered for the requested key/tag. */
    object NotScheduled : LightJobState
}

private fun WorkInfo?.toLightJobState(): LightJobState {
    if (this == null) return LightJobState.NotScheduled
    val output = outputData.keyValueMap
        .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
        .toMap()
    return when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> LightJobState.Enqueued
        WorkInfo.State.RUNNING -> LightJobState.Running
        WorkInfo.State.SUCCEEDED -> LightJobState.Succeeded(output)
        WorkInfo.State.FAILED -> LightJobState.Failed(output)
        WorkInfo.State.CANCELLED -> LightJobState.Cancelled
    }
}

private fun LightJobState.isTerminal(): Boolean =
    this is LightJobState.Succeeded || this is LightJobState.Failed || this is LightJobState.Cancelled

private const val LIGHT_JOB_KEY_PARAM = "__light_job_key__"

private fun Map<String, String?>.toData(): Data {
    val pairs = entries.map { it.key to it.value }.toTypedArray()
    return workDataOf(*pairs)
}

private fun Data.toStringMap(): Map<String, String> =
    keyValueMap.mapValues { it.toString() }.toMap()

object LightWork {
    /**
     * Schedule a [LightJob] to run once.
     *
     * If another instance is already enqueued under the same uniqueness slot ([tag] if
     * provided, otherwise [jobKey]), it is replaced.
     *
     * @param lightContext context — obtain one from a [LightScreen] or
     *   another Light SDK entry point
     * @param jobKey the [LightJob.key] of the registered handler
     * @param inputData key/value pairs forwarded to the handler
     * @param tag optional override for the uniqueness slot. Use only when you genuinely
     *   need multiple concurrent instances of the same job. Most callers should leave
     *   this null.
     * @return true if enqueued; false if no @LightJob is registered for [jobKey]
     */
    fun enqueue(
        lightContext: SealedLightContext,
        jobKey: String,
        inputData: Map<String, String> = emptyMap(),
        tag: String? = null,
    ): Boolean {
        if (LightSdkRegistry.jobs[jobKey] == null) return false

        val payload = inputData.toMutableMap().also { it[LIGHT_JOB_KEY_PARAM] = jobKey }.toData()
        val request = OneTimeWorkRequestBuilder<LightJobWorkManagerWrapper>()
            .setInputData(payload)
            .build()
        WorkManager.getInstance(lightContext.androidContext).enqueueUniqueWork(
            tag ?: jobKey,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return true
    }

    /**
     * Schedule a [LightJob] to run on a repeating interval.
     *
     * @param lightContext the sandboxed context
     * @param jobKey the [LightJob.key] of the registered handler
     * @param repeatInterval how often the job should run. By default there's a 15 minute minimum. shorter intervals are rounded up.
     * @param inputData key/value pairs forwarded to the handler on every run
     * @param tag see [enqueue]. Defaults to [jobKey] — the common case for a periodic
     *   job is exactly one schedule per app.
     * @return true if enqueued; false if no @LightJob is registered for [jobKey]
     */
    fun enqueuePeriodic(
        lightContext: SealedLightContext,
        jobKey: String,
        repeatInterval: Duration,
        inputData: Map<String, String> = emptyMap(),
        tag: String? = null,
    ): Boolean {
        if (LightSdkRegistry.jobs[jobKey] == null) return false

        val payload = inputData.toMutableMap().also { it[LIGHT_JOB_KEY_PARAM] = jobKey }.toData()
        val request = PeriodicWorkRequestBuilder<LightJobWorkManagerWrapper>(repeatInterval.toJavaDuration())
            .setInputData(payload)
            .build()
        WorkManager.getInstance(lightContext.androidContext).enqueueUniquePeriodicWork(
            tag ?: jobKey,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        return true
    }

    /**
     * Cancel any one-time or periodic schedule under the given uniqueness slot. Pass the
     * `jobKey` to cancel a default-scheduled job, or the `tag` you supplied to [enqueue]
     * / [enqueuePeriodic] to cancel a tagged instance.
     */
    fun cancel(lightContext: SealedLightContext, jobKeyOrTag: String) {
        WorkManager.getInstance(lightContext.androidContext).cancelUniqueWork(jobKeyOrTag)
    }

    /**
     * Watch state changes for a scheduled job.
     *
     * Emits a new [LightJobState] each time the underlying work transitions (e.g. from
     * [LightJobState.Enqueued] to [LightJobState.Running] to [LightJobState.Succeeded]).
     * For periodic jobs the flow continues to emit through every cycle.
     *
     * Collect on a Compose-scoped coroutine (e.g. `LaunchedEffect`) or `viewModelScope`.
     *
     * @param lightContext the sandboxed context
     * @param jobKeyOrTag the [LightJob.key] or custom `tag` used at enqueue time
     */

    fun observe(lightContext: SealedLightContext, jobKeyOrTag: String): Flow<LightJobState> =
        WorkManager.getInstance(lightContext.androidContext)
            .getWorkInfosForUniqueWorkFlow(jobKeyOrTag)
            .map { infos -> infos.lastOrNull().toLightJobState() }
            .distinctUntilChanged()

    /**
     * Read the current state of a scheduled job, without subscribing.
     *
     * Returns [LightJobState.NotScheduled] if nothing has been enqueued under
     * [jobKeyOrTag], or if WorkManager has pruned the record.
     */
    suspend fun getState(lightContext: SealedLightContext, jobKeyOrTag: String): LightJobState =
        observe(lightContext, jobKeyOrTag).first()

    /**
     * Suspend until the job reaches a terminal state ([LightJobState.Succeeded],
     * [LightJobState.Failed], or [LightJobState.Cancelled]) and return it.
     *
     * For periodic jobs this returns after the first cycle completes — subsequent cycles
     * keep running and can be watched with [observe].
     */
    suspend fun awaitCompletion(lightContext: SealedLightContext, jobKeyOrTag: String): LightJobState =
        observe(lightContext, jobKeyOrTag).first { it.isTerminal() }
}

/** WorkManager bridge — not for direct use by tools. */
class LightJobWorkManagerWrapper(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(LIGHT_JOB_KEY_PARAM) ?: return Result.failure()
        val handler = LightSdkRegistry.jobs[key] ?: return Result.failure()
        val input = inputData.toStringMap() - LIGHT_JOB_KEY_PARAM
        val sealedLightContext = SealedLightContext(applicationContext)
        return when (val result = handler(sealedLightContext, input)) {
            is LightJobResult.Success -> {
                val outputData = result.outputData +
                        (LIGHT_SUCCESS_OUTPUT_FILE to result.outputFilePath) +
                        (LIGHT_SUCCESS_MESSAGE to result.message)
                Result.success(outputData.toData())
            }
            is LightJobResult.Retry -> Result.retry()
            is LightJobResult.Error -> {
                val outputData = result.outputData + (LIGHT_FAIL_REASON to result.reason)
                Result.failure(outputData.toData())
            }
        }
    }
}
