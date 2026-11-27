package com.thelightphone.sdk.server

import com.thelightphone.sdk.trust.AdmissionException
import com.thelightphone.sdk.trust.AdmissionRemovalOutcome
import com.thelightphone.sdk.trust.DenyReason
import com.thelightphone.sdk.trust.ToolEvaluation

interface ToolTrustEvaluation {
    fun evaluate(meta: LightSdkServer.ApkMetadata, level: ClientFilterLevel): ToolEvaluation

    fun invalidatePackage(packageName: String) {}

    fun onPackageRemoved(packageName: String, replacing: Boolean): AdmissionRemovalOutcome? = null
}

object UnavailableToolTrustEvaluation : ToolTrustEvaluation {
    override fun evaluate(meta: LightSdkServer.ApkMetadata, level: ClientFilterLevel) =
        unavailableOrAllowAll(level, DenyReason.TrustUnavailable)
}

internal fun unavailableOrAllowAll(level: ClientFilterLevel, reason: DenyReason): ToolEvaluation =
    if (level == ClientFilterLevel.AllowAllApks) {
        ToolTrustRuntime.exceptionWithoutArtifact(AdmissionException.AllowAll)
    } else {
        ToolTrustRuntime.unavailable(reason)
    }
