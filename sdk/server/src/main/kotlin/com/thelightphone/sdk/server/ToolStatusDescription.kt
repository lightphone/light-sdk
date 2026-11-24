package com.thelightphone.sdk.server

import com.thelightphone.sdk.trust.AdmissionException
import com.thelightphone.sdk.trust.DenyReason
import com.thelightphone.sdk.trust.LightInstallDecision
import com.thelightphone.sdk.trust.ToolEvaluationOutcome

data class ToolStatusDescription(val state: String, val reason: String, val attention: String?)

fun ToolEnforcementResult.describeStatus(): ToolStatusDescription {
    val reason = when (val decision = evaluation.decision) {
        LightInstallDecision.Allow -> when ((evaluation.outcome as? ToolEvaluationOutcome.Bypass)?.exception) {
            AdmissionException.AllowAll -> "Allowed by your All Tools setting."
            AdmissionException.DeveloperHash -> "Allowed by your developer signing key."
            AdmissionException.EmulatorDevelopmentKey -> "Allowed for emulator development."
            null -> "This tool meets your current tool settings."
        }
        is LightInstallDecision.Kill -> decision.reason.ifBlank { "Restricted by the current tool policy." }
        is LightInstallDecision.Deny -> when (decision.reason) {
            DenyReason.TrustUnavailable -> "Tool trust information is unavailable."
            DenyReason.ArtifactUnavailable -> "This tool could not be checked."
            DenyReason.AdmissionUnavailable -> "This tool's admission record could not be confirmed."
            DenyReason.NotApproved -> "This tool is not currently approved."
            DenyReason.BelowMinVersion -> "This version is no longer approved. Update the tool."
            DenyReason.SignerNotApprovedForTool -> "This tool's signing key is not approved."
            DenyReason.FilteredOut -> "Your Allowed Tools setting excludes this tool."
            DenyReason.UntrustedStampCert -> "This tool's source stamp is not trusted."
            DenyReason.MalformedStatement -> "This tool's trust statement is invalid."
            DenyReason.NotLightBuilt -> "This tool has no verified Light build information."
            DenyReason.BadAttestation -> "This tool's source stamp could not be verified."
            DenyReason.StatementNotForThisApk -> "This tool does not match its trust statement."
            DenyReason.VersionCodeMismatch -> "This version does not match its trust statement."
        }
    }
    return ToolStatusDescription(
        state = when (containment) {
            ToolContainment.Allowed -> "Allowed"
            ToolContainment.Disabled -> "Disabled"
            ToolContainment.PurgeRequested -> "Removal requested"
            ToolContainment.OwnershipConflict, ToolContainment.Failed -> "Needs attention"
        },
        reason = reason,
        attention = when (containment) {
            ToolContainment.OwnershipConflict -> "Access remains restricted. The previous disabled state could not be safely restored."
            ToolContainment.Failed -> "Access remains restricted. A required action could not be completed."
            else -> null
        },
    )
}
