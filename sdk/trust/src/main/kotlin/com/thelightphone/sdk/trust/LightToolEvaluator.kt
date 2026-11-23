package com.thelightphone.sdk.trust

/** A verified-or-not snapshot of one on-disk APK, ready for policy evaluation. */
data class ToolArtifact(
    val packageName: String,
    val apkSha256: String,
    val signerSha256: String,
    val versionCode: Long,
    val stamp: StampResult,
    val statement: StatementReadResult,
    /** Continuity evidence for an installed package; `null` for an archive that isn't installed yet. */
    val installationFingerprint: InstallationFingerprint? = null,
)

/**
 * How an artifact's identity was established.
 * [LightStamped]: a verified, trusted Light stamp with a statement matching this exact APK.
 * [EmulatorDevelopment]: not stamped, but signed by a recognized emulator/dev-build key.
 * [Unverified]: neither — a plain third-party APK.
 */
enum class ToolProvenance { LightStamped, EmulatorDevelopment, Unverified }

/**
 * Result of evaluating one [ToolArtifact].
 *
 * @property decision The effective admission verdict, including any bypass.
 * @property approvedByBundle Whether the bundle's allowlist covers this exact
 * artifact, even if a block also matches.
 */
data class ToolEvaluation(
    val provenance: ToolProvenance,
    val approvedByBundle: Boolean,
    val bundleVersion: Long?,
    val outcome: ToolEvaluationOutcome,
) {
    val decision: LightInstallDecision
        get() = when (val result = outcome) {
            is ToolEvaluationOutcome.PolicyDecision -> result.decision
            is ToolEvaluationOutcome.Bypass -> LightInstallDecision.Allow
        }

    /**
     * Requires current bundle approval and no matching block.
     * An admission exception alone grants no access to protected APIs.
     */
    val mayUseApprovalProtectedApi: Boolean
        get() = approvedByBundle &&
            decision !is LightInstallDecision.Kill &&
            outcome !is ToolEvaluationOutcome.BlockOverridden
}

sealed interface ToolEvaluationOutcome {
    /** A policy [LightInstallDecision.Allow] is eligible for normal admission recording. */
    data class PolicyDecision(
        val decision: LightInstallDecision,
    ) : ToolEvaluationOutcome

    /** An exception granted admission, so normal admission recording is skipped. */
    sealed interface Bypass : ToolEvaluationOutcome {
        val exception: AdmissionException
    }

    /**
     * An exception grants admission without overriding a known bundle block.
     * This includes fallback paths where block evaluation was unavailable.
     */
    data class ExceptionGranted(
        override val exception: AdmissionException,
    ) : Bypass

    /**
     * An exception grants admission despite a matching bundle block.
     * The block still prevents access to approval-protected APIs.
     */
    data class BlockOverridden(
        override val exception: AdmissionException,
        val block: LightInstallDecision.Kill,
    ) : Bypass
}

object LightToolEvaluator {
    /**
     * Runs the full install/runtime decision over a verified [artifact]: source-stamp trust,
     * historical admission (so a later bundle revocation can't retroactively uninstall an
     * already-admitted tool), bundle approval/block matching, and bypass exceptions, in that order.
     *
     * Null [state] means unavailable trust, not an available state with no bundle yet.
     */
    fun evaluate(
        artifact: ToolArtifact,
        state: TrustState?,
        level: LightTrustFilterLevel,
        admission: AdmissionRecord? = null,
        developerSignerBypass: Boolean = false,
        emulatorDevelopmentSigner: Boolean = false,
    ): ToolEvaluation {
        val statement = (artifact.statement as? StatementReadResult.Present)?.statement

        // True when `admission` was earned by this exact artifact on this exact installation
        // (same hash, signer, version, and installation fingerprint) with no bypass involved.
        // Used below to grandfather a stamp cert whose trust was later revoked: an admission
        // that already stands can survive revocation, but a fresh install cannot.
        val historicalNormalAdmission = admission?.let {
            it.packageName == artifact.packageName && it.apkSha256 == artifact.apkSha256 &&
                it.signerSha256 == artifact.signerSha256 && it.versionCode == artifact.versionCode &&
                artifact.installationFingerprint != null && it.installationFingerprint == artifact.installationFingerprint &&
                it.exception == null
        } == true

        val verified = artifact.stamp as? StampResult.Verified
        val stampTrusted = verified != null && (historicalNormalAdmission ||
            verified.certSha256 in state?.trustedStampCerts.orEmpty())
        // From here on, a verified-but-untrusted stamp (revoked cert, not grandfathered) is
        // treated exactly like a stamp that never verified: it must not read as "Light built".
        val effectiveStamp = if (verified != null && !stampTrusted) StampResult.NotVerified else artifact.stamp

        // Local alias fixing the stamp/statement/APK identity so only filter level and bundle vary below.
        fun decide(filter: LightTrustFilterLevel, bundle: LightTrustBundle?) = LightInstallPolicy.decide(
            effectiveStamp, statement, artifact.signerSha256, { artifact.apkSha256 },
            artifact.versionCode, bundle, filter,
        )

        // `normal`: the decision at the caller's actual filter level — this is what `decision` is based on.
        val normal = if (state == null) LightInstallDecision.Deny(DenyReason.TrustUnavailable)
            else decide(level, state.bundle)
        // `block`: the decision at the strictest level that still consults the bundle, computed
        // purely to surface a kill even when `level` is lax enough (or bypassed) to otherwise allow.
        val block = if (state == null) null
            else decide(LightTrustFilterLevel.AllowLightSignedApks, state.bundle) as? LightInstallDecision.Kill

        // A "real" Light-built artifact: trusted stamp, and the statement actually describes this
        // exact package/signer/version (not just any statement that happens to verify).
        val structurallyValid = stampTrusted && statement != null &&
            statement.tool.id == artifact.packageName && statement.signerSha256 == artifact.signerSha256 &&
            statement.tool.versionCode == artifact.versionCode
        // Approval is checked with blocks cleared: `block` above already captures kill semantics,
        // so approval itself should reflect only the bundle's `allow` list, not its `block` list.
        val approved = state != null && structurallyValid &&
            decide(LightTrustFilterLevel.AllowLightApprovedApks, state.bundle?.copy(block = emptyList())) == LightInstallDecision.Allow

        // Precedence: an explicit "allow all" always wins, then a developer's own signer, then
        // the narrower emulator-signer carve-out (Signed level only, and never over a block).
        val exception = when {
            level == LightTrustFilterLevel.AllowAllApks -> AdmissionException.AllowAll
            developerSignerBypass -> AdmissionException.DeveloperHash
            state != null && emulatorDevelopmentSigner && block == null &&
                level == LightTrustFilterLevel.AllowLightSignedApks -> AdmissionException.EmulatorDevelopmentKey
            else -> null
        }
        val decision = when {
            // `normal` can only be Allow here because LightInstallPolicy saw a statement whose
            // signer matched the APK's reported signer; if that statement names a different
            // *package* than the one being installed, it was never authenticated for this APK.
            normal == LightInstallDecision.Allow && statement != null && statement.tool.id != artifact.packageName ->
                LightInstallDecision.Deny(DenyReason.StatementNotForThisApk)
            // Distinguishes "stamp verified but its cert isn't trusted" from a generic bad
            // attestation, so callers can tell revocation apart from a broken/forged stamp.
            normal is LightInstallDecision.Deny && normal.reason == DenyReason.BadAttestation &&
                verified != null && !stampTrusted -> LightInstallDecision.Deny(DenyReason.UntrustedStampCert)
            // Distinguishes "no statement at all" from "a statement that failed to parse".
            normal is LightInstallDecision.Deny && normal.reason == DenyReason.NotLightBuilt &&
                artifact.statement == StatementReadResult.Malformed -> LightInstallDecision.Deny(DenyReason.MalformedStatement)
            else -> normal
        }
        val provenance = when {
            structurallyValid -> ToolProvenance.LightStamped
            emulatorDevelopmentSigner -> ToolProvenance.EmulatorDevelopment
            else -> ToolProvenance.Unverified
        }
        val outcome = when {
            exception == null -> ToolEvaluationOutcome.PolicyDecision(decision)
            block == null -> ToolEvaluationOutcome.ExceptionGranted(exception)
            else -> ToolEvaluationOutcome.BlockOverridden(exception, block)
        }
        return ToolEvaluation(provenance, approved, state?.version, outcome)
    }
}
