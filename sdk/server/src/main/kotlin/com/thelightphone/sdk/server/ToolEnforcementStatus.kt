package com.thelightphone.sdk.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ToolEnforcementSnapshot(
    val revision: Long = 0,
    val checking: Boolean = true,
    val results: List<ToolEnforcementResult> = emptyList(),
    val failed: Boolean = false,
)

internal sealed interface ToolPurgeOutcome {
    data class Failed(val reason: String) : ToolPurgeOutcome

    data object Skipped : ToolPurgeOutcome
}

internal class ToolEnforcementStatus {
    private val finishedPurges = mutableMapOf<Pair<String, String>, ToolPurgeOutcome>()
    private val current = MutableStateFlow(ToolEnforcementSnapshot())
    val snapshot = current.asStateFlow()

    @Synchronized
    fun request(): Long {
        val revision = current.value.revision + 1
        current.value = ToolEnforcementSnapshot(revision)
        return revision
    }

    @Synchronized
    fun complete(revision: Long, results: List<ToolEnforcementResult>) {
        if (revision != current.value.revision) return
        val currentAttempts = results.associate { it.packageName to it.purgeAttemptId }
        finishedPurges.keys.removeAll { (packageName, attemptId) ->
            currentAttempts[packageName] != attemptId
        }
        current.value =
            ToolEnforcementSnapshot(
                revision,
                checking = false,
                results = results.map(::withPurgeOutcome),
            )
    }

    @Synchronized
    fun fail(revision: Long) {
        if (revision == current.value.revision)
            current.value = ToolEnforcementSnapshot(revision, checking = false, failed = true)
    }

    @Synchronized
    fun purgeFinished(packageName: String, attemptId: String, outcome: ToolPurgeOutcome) {
        val before = current.value
        val result = before.results.firstOrNull { it.packageName == packageName }
        if (result?.purgeAttemptId == attemptId || before.checking) {
            finishedPurges[packageName to attemptId] = outcome
            current.value = before.copy(results = before.results.map(::withPurgeOutcome))
        }
    }

    private fun withPurgeOutcome(result: ToolEnforcementResult): ToolEnforcementResult {
        val attemptId = result.purgeAttemptId ?: return result
        if (result.containment != ToolContainment.PurgeRequested) return result
        return when (val outcome = finishedPurges[result.packageName to attemptId]) {
            null -> result
            is ToolPurgeOutcome.Failed ->
                result.copy(
                    containment = ToolContainment.Failed,
                    failures = result.failures + outcome.reason,
                )
            ToolPurgeOutcome.Skipped -> result.copy(containment = ToolContainment.PurgeSkipped)
        }
    }
}
