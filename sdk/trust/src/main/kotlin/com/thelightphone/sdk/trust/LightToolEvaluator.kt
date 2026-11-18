package com.thelightphone.sdk.trust

data class ToolArtifact(
    val packageName: String,
    val apkSha256: String,
    val signerSha256: String,
    val versionCode: Long,
    val stamp: StampResult,
    val statement: StatementReadResult,
    val installationFingerprint: InstallationFingerprint? = null,
)

enum class ToolProvenance { LightStamped, EmulatorDevelopment, Unverified }

data class ToolEvaluation(
    val provenance: ToolProvenance,
    val currentlyApproved: Boolean,
    val block: LightInstallDecision.Kill?,
    val decision: LightInstallDecision,
    val exception: AdmissionException?,
    val bundleVersion: Long?,
) {
    val mayUseApprovalProtectedApi: Boolean
        get() = currentlyApproved && block == null
}

object LightToolEvaluator {
    /** Null state means unavailable trust, not an available state with no bundle yet. */
    fun evaluate(
        artifact: ToolArtifact,
        state: TrustState?,
        level: LightTrustFilterLevel,
        admission: AdmissionRecord? = null,
        developerSignerBypass: Boolean = false,
        emulatorDevelopmentSigner: Boolean = false,
    ): ToolEvaluation {
        val statement = (artifact.statement as? StatementReadResult.Present)?.statement
        val historicalNormalAdmission = admission?.let {
            it.packageName == artifact.packageName && it.apkSha256 == artifact.apkSha256 &&
                it.signerSha256 == artifact.signerSha256 && it.versionCode == artifact.versionCode &&
                artifact.installationFingerprint != null && it.installationFingerprint == artifact.installationFingerprint &&
                it.exception == null
        } == true
        val verified = artifact.stamp as? StampResult.Verified
        val stampTrusted = verified != null && (historicalNormalAdmission ||
            verified.certSha256 in state?.trustedStampCerts.orEmpty())
        val effectiveStamp = if (verified != null && !stampTrusted) StampResult.NotVerified else artifact.stamp
        fun decide(filter: LightTrustFilterLevel, bundle: LightTrustBundle?) = LightInstallPolicy.decide(
            effectiveStamp, statement, artifact.signerSha256, { artifact.apkSha256 },
            artifact.versionCode, bundle, filter,
        )
        val normal = if (state == null) LightInstallDecision.Deny(DenyReason.TrustUnavailable)
            else decide(level, state.bundle)
        val block = if (state == null) null
            else decide(LightTrustFilterLevel.AllowLightSignedApks, state.bundle) as? LightInstallDecision.Kill
        val structurallyValid = stampTrusted && statement != null &&
            statement.tool.id == artifact.packageName && statement.signerSha256 == artifact.signerSha256 &&
            statement.tool.versionCode == artifact.versionCode
        val approved = state != null && structurallyValid &&
            decide(LightTrustFilterLevel.AllowLightApprovedApks, state.bundle?.copy(block = emptyList())) == LightInstallDecision.Allow
        val exception = when {
            level == LightTrustFilterLevel.AllowAllApks -> AdmissionException.AllowAll
            developerSignerBypass -> AdmissionException.DeveloperHash
            state != null && emulatorDevelopmentSigner && block == null &&
                level == LightTrustFilterLevel.AllowLightSignedApks -> AdmissionException.EmulatorDevelopmentKey
            else -> null
        }
        val decision = when {
            exception != null -> LightInstallDecision.Allow
            normal == LightInstallDecision.Allow && statement != null && statement.tool.id != artifact.packageName ->
                LightInstallDecision.Deny(DenyReason.StatementNotForThisApk)
            normal is LightInstallDecision.Deny && normal.reason == DenyReason.BadAttestation &&
                verified != null && !stampTrusted -> LightInstallDecision.Deny(DenyReason.UntrustedStampCert)
            normal is LightInstallDecision.Deny && normal.reason == DenyReason.NotLightBuilt &&
                artifact.statement == StatementReadResult.Malformed -> LightInstallDecision.Deny(DenyReason.MalformedStatement)
            else -> normal
        }
        return ToolEvaluation(
            when {
                structurallyValid -> ToolProvenance.LightStamped
                emulatorDevelopmentSigner -> ToolProvenance.EmulatorDevelopment
                else -> ToolProvenance.Unverified
            }, approved, block, decision, exception, state?.version,
        )
    }
}
