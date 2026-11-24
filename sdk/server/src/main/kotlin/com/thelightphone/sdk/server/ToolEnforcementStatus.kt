package com.thelightphone.sdk.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ToolEnforcementSnapshot(
    val revision: Long = 0,
    val checking: Boolean = true,
    val results: List<ToolEnforcementResult> = emptyList(),
    val failed: Boolean = false,
)

internal class ToolEnforcementStatus {
    private val current = MutableStateFlow(ToolEnforcementSnapshot())
    val snapshot = current.asStateFlow()

    @Synchronized fun request(): Long {
        val revision = current.value.revision + 1
        current.value = ToolEnforcementSnapshot(revision)
        return revision
    }

    @Synchronized fun complete(revision: Long, results: List<ToolEnforcementResult>) {
        if (revision == current.value.revision)
            current.value = ToolEnforcementSnapshot(revision, checking = false, results = results.toList())
    }

    @Synchronized fun fail(revision: Long) {
        if (revision == current.value.revision)
            current.value = ToolEnforcementSnapshot(revision, checking = false, failed = true)
    }

    @Synchronized fun purgeFailed(packageName: String, bundleVersion: Long, status: Int) {
        current.value = current.value.copy(results = current.value.results.map {
            if (it.packageName == packageName && it.containment == ToolContainment.PurgeRequested &&
                it.evaluation.bundleVersion == bundleVersion)
                it.copy(containment = ToolContainment.Failed, failures = it.failures + "Uninstall failed: status=$status")
            else it
        })
    }
}
