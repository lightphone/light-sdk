package com.thelightphone.sdk.server

import com.thelightphone.sdk.trust.BlockAction
import com.thelightphone.sdk.trust.InstallationFingerprint
import com.thelightphone.sdk.trust.LightInstallDecision
import com.thelightphone.sdk.trust.ToolEvaluation

data class OwnedToolRestriction(
    val packageName: String,
    val priorEnabledState: Int,
    val ownerToken: String,
    val installationFingerprint: InstallationFingerprint? = null,
    val applied: Boolean = false,
)

/** Host implementations own atomic persistence, platform privileges and ownership evidence. */
interface ToolRestrictionBackend {
    fun ownedRestriction(packageName: String): OwnedToolRestriction?

    fun revokeSdkAuthorization(packageName: String)

    fun cleanupHostResources(packageName: String)

    /**
     * Persist ownership/prior state before changing an enabled package; never adopt another owner's
     * restriction.
     */
    fun disable(packageName: String)

    fun restrictionStillOwned(record: OwnedToolRestriction): Boolean

    fun restore(record: OwnedToolRestriction)

    fun purge(packageName: String): String

    fun allowSdkAuthorization(packageName: String) {}
}

enum class ToolContainment {
    Allowed,
    Disabled,
    PurgeRequested,
    PurgeSkipped,
    OwnershipConflict,
    Failed,
}

data class ToolEnforcementResult(
    val packageName: String,
    val evaluation: ToolEvaluation,
    val containment: ToolContainment,
    val failures: List<String> = emptyList(),
    val purgeAttemptId: String? = null,
)

/** Gate changes precede potentially slow cleanup/platform operations. */
class ToolEnforcementCoordinator(private val backend: ToolRestrictionBackend) {
    private val permitted = mutableSetOf<String>()
    @Volatile
    var initialReconciliationComplete: Boolean = false
        private set

    @Synchronized
    fun permitsAccess(packageName: String): Boolean =
        initialReconciliationComplete && packageName in permitted

    @Synchronized
    fun invalidate() {
        initialReconciliationComplete = false
    }

    @Synchronized
    fun reconcileAll(evaluations: List<Pair<String, ToolEvaluation>>): List<ToolEnforcementResult> {
        initialReconciliationComplete = false
        permitted.clear()
        return evaluations
            .map { (packageName, evaluation) -> reconcile(packageName, evaluation) }
            .also { initialReconciliationComplete = true }
    }

    @Synchronized
    fun reconcile(packageName: String, evaluation: ToolEvaluation): ToolEnforcementResult {
        val failures = mutableListOf<String>()
        fun attempt(operation: String, action: () -> Unit): Boolean =
            try {
                action()
                true
            } catch (error: Exception) {
                failures += "$operation: ${error.message ?: error.javaClass.simpleName}"
                false
            }
        if (evaluation.decision == LightInstallDecision.Allow) {
            val owned =
                try {
                    backend.ownedRestriction(packageName)
                } catch (error: Exception) {
                    permitted -= packageName
                    return ToolEnforcementResult(
                        packageName,
                        evaluation,
                        ToolContainment.Failed,
                        listOf("read restriction: ${error.message ?: error.javaClass.simpleName}"),
                    )
                }
            if (owned != null) {
                val stillOwned =
                    try {
                        backend.restrictionStillOwned(owned)
                    } catch (_: Exception) {
                        false
                    }
                if (!stillOwned) {
                    permitted -= packageName
                    return ToolEnforcementResult(
                        packageName,
                        evaluation,
                        ToolContainment.OwnershipConflict,
                    )
                }
                if (!attempt("restore") { backend.restore(owned) }) {
                    permitted -= packageName
                    return ToolEnforcementResult(
                        packageName,
                        evaluation,
                        ToolContainment.Failed,
                        failures,
                    )
                }
            }
            if (
                !attempt("allow SDK authorization") { backend.allowSdkAuthorization(packageName) }
            ) {
                permitted -= packageName
                return ToolEnforcementResult(
                    packageName,
                    evaluation,
                    ToolContainment.Failed,
                    failures,
                )
            }
            permitted += packageName
            return ToolEnforcementResult(packageName, evaluation, ToolContainment.Allowed)
        }

        permitted -= packageName
        attempt("revoke SDK authorization") { backend.revokeSdkAuthorization(packageName) }
        attempt("cleanup host resources") { backend.cleanupHostResources(packageName) }
        // Bypass outcomes always surface as Allow via the `decision` getter, so a non-null
        // Kill here can only come from an un-bypassed PolicyDecision - already corroborated
        // by the accepted bundle policy itself, with nothing separate left to cross-check.
        val kill = evaluation.decision as? LightInstallDecision.Kill
        val purge = kill?.action == BlockAction.Purge && evaluation.bundleVersion != null
        if (kill?.action == BlockAction.Purge && !purge) {
            failures += "purge lacks matching accepted policy evidence"
        }
        var purgeAttemptId: String? = null
        val platformSucceeded =
            if (purge) {
                val disabled = attempt("disable pending purge") { backend.disable(packageName) }
                val requested = attempt("purge") { purgeAttemptId = backend.purge(packageName) }
                disabled && requested
            } else attempt("disable") { backend.disable(packageName) }
        val containment =
            when {
                !platformSucceeded || failures.isNotEmpty() -> ToolContainment.Failed
                purge -> ToolContainment.PurgeRequested
                else -> ToolContainment.Disabled
            }
        return ToolEnforcementResult(
            packageName,
            evaluation,
            containment,
            failures.toList(),
            purgeAttemptId,
        )
    }
}
