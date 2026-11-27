package com.thelightphone.sdk.server

import com.thelightphone.sdk.trust.AdmissionException
import com.thelightphone.sdk.trust.ToolEvaluation
import com.thelightphone.sdk.trust.ToolEvaluationOutcome

interface ToolEnforcement {
    /** Called only after the fresh trust evaluation allows access. */
    fun permitsAccess(packageName: String, evaluation: ToolEvaluation): Boolean

    fun requestReconciliation() {}

    fun onPackageRemoved(packageName: String, replacing: Boolean) {}

    fun onPurgeOutcome(packageName: String, attemptId: String, outcome: ToolPurgeOutcome) {}
}

internal object NoToolEnforcement : ToolEnforcement {
    override fun permitsAccess(packageName: String, evaluation: ToolEvaluation) = true
}

internal object RequiredToolEnforcement : ToolEnforcement {
    override fun permitsAccess(packageName: String, evaluation: ToolEvaluation): Boolean {
        val exception = (evaluation.outcome as? ToolEvaluationOutcome.Bypass)?.exception
        return exception == AdmissionException.AllowAll || exception == AdmissionException.DeveloperHash
    }
}
