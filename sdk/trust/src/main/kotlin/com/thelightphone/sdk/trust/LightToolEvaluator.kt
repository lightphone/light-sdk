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
 * The full result of evaluating one [ToolArtifact]: not just "can it install" ([decision]), but
 * also whether it is *currently* approved independent of [decision] (so a caller can tell a
 * blocked-but-previously-approved tool apart from one that was never approved at all).
 *
 * Each case carries only the fields that can be meaningful for it: a bypass [AdmissionException]
 * exists only on [Bypassed], and a bundle block is only worth reporting separately on [Bypassed]
 * (everywhere else a block already shows up as [Killed]).
 */
sealed interface ToolEvaluation {
    val provenance: ToolProvenance
    /** Whether the bundle approves this exact artifact right now, regardless of [decision]. */
    val currentlyApproved: Boolean
    val bundleVersion: Long?
    val decision: LightInstallDecision
    /** True only for a real, unbypassed, unblocked approval — the gate for APIs that must not be fooled by a bypass exception. */
    val mayUseApprovalProtectedApi: Boolean

    /**
     * Passed policy on its own merits at the requested level. Never blocked: outside
     * `AllowAllApks` (which is itself a bypass) a matching block always kills.
     */
    data class Allowed(
        override val provenance: ToolProvenance,
        override val currentlyApproved: Boolean,
        override val bundleVersion: Long?,
    ) : ToolEvaluation {
        override val decision get() = LightInstallDecision.Allow
        override val mayUseApprovalProtectedApi get() = currentlyApproved
    }

    /** Allowed only because of [exception]; policy on its own would not have been consulted or would have refused. */
    data class Bypassed(
        val exception: AdmissionException,
        /** Set when the strictest filter level would kill this artifact, even though [exception] allows it. */
        val block: LightInstallDecision.Kill?,
        override val provenance: ToolProvenance,
        override val currentlyApproved: Boolean,
        override val bundleVersion: Long?,
    ) : ToolEvaluation {
        override val decision get() = LightInstallDecision.Allow
        override val mayUseApprovalProtectedApi get() = currentlyApproved && block == null
    }

    data class Denied(
        val reason: DenyReason,
        override val provenance: ToolProvenance,
        override val currentlyApproved: Boolean,
        override val bundleVersion: Long?,
    ) : ToolEvaluation {
        override val decision get() = LightInstallDecision.Deny(reason)
        override val mayUseApprovalProtectedApi get() = currentlyApproved
    }

    /** A bundle block matched; [currentlyApproved] still reflects the bundle's allow list alone. */
    data class Killed(
        val kill: LightInstallDecision.Kill,
        override val provenance: ToolProvenance,
        override val currentlyApproved: Boolean,
        override val bundleVersion: Long?,
    ) : ToolEvaluation {
        override val decision get() = kill
        override val mayUseApprovalProtectedApi get() = false
    }

    companion object {
        /** Maps an unbypassed policy [decision] onto its case. */
        fun of(
            decision: LightInstallDecision,
            provenance: ToolProvenance,
            currentlyApproved: Boolean,
            bundleVersion: Long?,
        ): ToolEvaluation = when (decision) {
            LightInstallDecision.Allow -> Allowed(provenance, currentlyApproved, bundleVersion)
            is LightInstallDecision.Deny -> Denied(decision.reason, provenance, currentlyApproved, bundleVersion)
            is LightInstallDecision.Kill -> Killed(decision, provenance, currentlyApproved, bundleVersion)
        }
    }
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
        // Any bypass exception grants Allow outright, independent of `decision`.
        return if (exception != null) ToolEvaluation.Bypassed(exception, block, provenance, approved, state?.version)
            else ToolEvaluation.of(decision, provenance, approved, state?.version)
    }
}
