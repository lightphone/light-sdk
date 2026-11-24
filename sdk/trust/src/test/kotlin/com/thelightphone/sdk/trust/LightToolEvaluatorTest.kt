package com.thelightphone.sdk.trust

import kotlin.test.*
import kotlin.time.Instant

class LightToolEvaluatorTest {
    private val signer = "a".repeat(64)
    private val stampCert = "b".repeat(64)
    private val hash = "c".repeat(64)
    private val statement =
        LightTrustStatement(
            1,
            LightTrustTool("example.tool", 7, "1", "https://example.test", "abc"),
            "sdk",
            "dev",
            signer,
            "build",
            "2026-11-17T00:00:00Z",
        )
    private val fingerprint = InstallationFingerprint("/data/app/example/base.apk", 1, 1)
    private val artifact =
        ToolArtifact(
            "example.tool",
            hash,
            signer,
            7,
            StampResult.Verified(stampCert, emptyList()),
            StatementReadResult.Present(statement),
            fingerprint,
        )
    private val bundle =
        LightTrustBundle(
            1,
            4,
            "2026-11-17T00:00:00Z",
            listOf(
                TrustApproval("example.tool", signer, 0, listOf(ApprovedArtifact.BuildId("build")))
            ),
            emptyList(),
            setOf(stampCert),
            emptySet(),
        )
    private val state = TrustState(bundle, setOf(stampCert))
    private val record =
        AdmissionRecord(
            "example.tool",
            hash,
            signer,
            "example.tool",
            7,
            3,
            LightTrustFilterLevel.AllowLightSignedApks,
            Instant.EPOCH,
            installationFingerprint = fingerprint,
        )

    private fun evaluate(
        artifact: ToolArtifact = this.artifact,
        state: TrustState? = this.state,
        level: LightTrustFilterLevel = LightTrustFilterLevel.AllowLightApprovedApks,
        admission: AdmissionRecord? = null,
        developer: Boolean = false,
        emulator: Boolean = false,
    ) = LightToolEvaluator.evaluate(artifact, state, level, admission, developer, emulator)

    @Test
    fun `each filter enforces its intended boundary`() {
        for (level in LightTrustFilterLevel.entries) {
            assertEquals(
                level != LightTrustFilterLevel.ExcludeAllApks,
                evaluate(level = level).decision == LightInstallDecision.Allow,
            )
        }
        val policy = evaluate()
        assertEquals(
            ToolEvaluationOutcome.PolicyDecision(LightInstallDecision.Allow),
            policy.outcome,
        )
        val bypass = evaluate(developer = true)
        assertEquals(
            ToolEvaluationOutcome.ExceptionGranted(AdmissionException.DeveloperHash),
            bypass.outcome,
        )
        assertTrue(bypass.mayUseApprovalProtectedApi)

        val unapproved = state.copy(bundle = bundle.copy(allow = emptyList()))
        assertEquals(
            LightInstallDecision.Deny(DenyReason.NotApproved),
            evaluate(state = unapproved).decision,
        )
        assertEquals(
            LightInstallDecision.Allow,
            evaluate(state = unapproved, level = LightTrustFilterLevel.AllowLightSignedApks)
                .decision,
        )
    }

    @Test
    fun `revocation refuses fresh artifact but preserves normal admission`() {
        val revoked = TrustState(bundle.copy(revokedStampCerts = setOf(stampCert)), emptySet())
        assertEquals(
            LightInstallDecision.Deny(DenyReason.UntrustedStampCert),
            evaluate(state = revoked).decision,
        )
        assertEquals(
            LightInstallDecision.Allow,
            evaluate(state = revoked, admission = record).decision,
        )
        assertEquals(
            LightInstallDecision.Deny(DenyReason.UntrustedStampCert),
            evaluate(state = revoked, admission = record.copy(apkSha256 = "d".repeat(64))).decision,
        )
    }

    @Test
    fun `historical exception cannot launder revoked provenance when exception ends`() {
        val revoked = TrustState(bundle, emptySet())
        for (exception in AdmissionException.entries) {
            assertEquals(
                LightInstallDecision.Deny(DenyReason.UntrustedStampCert),
                evaluate(state = revoked, admission = record.copy(exception = exception)).decision,
            )
        }
    }

    @Test
    fun `changed or missing installation evidence cannot grandfather a revoked stamp`() {
        val revoked = state.copy(trustedStampCerts = emptySet())
        for (current in
            listOf(
                null,
                fingerprint.copy(baseApkPath = "/data/app/reinstalled/base.apk"),
                fingerprint.copy(firstInstallTimeMillis = 2),
                fingerprint.copy(lastUpdateTimeMillis = 2),
            )) {
            assertEquals(
                LightInstallDecision.Deny(DenyReason.UntrustedStampCert),
                evaluate(
                        artifact = artifact.copy(installationFingerprint = current),
                        state = revoked,
                        admission = record,
                    )
                    .decision,
            )
        }
        assertEquals(
            LightInstallDecision.Allow,
            evaluate(state = revoked, admission = record).decision,
        )
        assertEquals(
            LightInstallDecision.Deny(DenyReason.UntrustedStampCert),
            evaluate(state = revoked, admission = record.copy(installationFingerprint = null))
                .decision,
        )
    }

    @Test
    fun `admission never freezes approval`() {
        assertEquals(
            LightInstallDecision.Deny(DenyReason.NotApproved),
            evaluate(
                    state = state.copy(bundle = bundle.copy(allow = emptyList())),
                    admission = record,
                )
                .decision,
        )
    }

    @Test
    fun `manifest package cannot borrow another tool identity under the same signer`() {
        val result = evaluate(artifact = artifact.copy(packageName = "another.tool"))
        assertEquals(LightInstallDecision.Deny(DenyReason.StatementNotForThisApk), result.decision)
        assertFalse(result.approvedByBundle)
        assertFalse(result.mayUseApprovalProtectedApi)
    }

    @Test
    fun `execution bypass preserves current approval and block inspection`() {
        val kill = LightInstallDecision.Kill(BlockAction.Purge, "recalled")
        val blocked =
            state.copy(
                bundle =
                    bundle.copy(
                        block =
                            listOf(
                                TrustBlock(
                                    BlockMatch.SignerSha256(signer),
                                    kill.action,
                                    kill.reason,
                                )
                            )
                    )
            )
        val policy = evaluate(state = blocked, admission = record)
        assertEquals(ToolEvaluationOutcome.PolicyDecision(kill), policy.outcome)
        assertTrue(policy.approvedByBundle)
        assertFalse(policy.mayUseApprovalProtectedApi)

        for ((level, exception) in
            listOf(
                LightTrustFilterLevel.AllowAllApks to AdmissionException.AllowAll,
                LightTrustFilterLevel.AllowLightApprovedApks to AdmissionException.DeveloperHash,
            )) {
            val result =
                evaluate(state = blocked, level = level, admission = record, developer = true)
            assertEquals(LightInstallDecision.Allow, result.decision)
            assertTrue(result.approvedByBundle)
            assertEquals(ToolEvaluationOutcome.BlockOverridden(exception, kill), result.outcome)
            assertFalse(result.mayUseApprovalProtectedApi)
        }
    }

    @Test
    fun `bypass cannot grant approval to unsigned or mismatched artifacts`() {
        for (input in
            listOf(
                artifact.copy(stamp = StampResult.NotPresent),
                artifact.copy(signerSha256 = "d".repeat(64)),
                artifact.copy(versionCode = 8),
            )) {
            val result = evaluate(artifact = input, developer = true)
            assertEquals(LightInstallDecision.Allow, result.decision)
            assertFalse(result.approvedByBundle)
            assertFalse(result.mayUseApprovalProtectedApi)
        }
    }

    @Test
    fun `trust unavailable denies without using stale purge policy unless bypassed`() {
        val result = evaluate(state = null, admission = record)
        assertEquals(LightInstallDecision.Deny(DenyReason.TrustUnavailable), result.decision)
        assertEquals(ToolEvaluationOutcome.PolicyDecision(result.decision), result.outcome)
        assertFalse(result.approvedByBundle)
        assertEquals(LightInstallDecision.Allow, evaluate(state = null, developer = true).decision)
        assertEquals(
            LightInstallDecision.Allow,
            evaluate(state = null, level = LightTrustFilterLevel.AllowAllApks).decision,
        )
    }

    @Test
    fun `emulator development support is Signed only and cannot override block`() {
        val unsigned =
            artifact.copy(stamp = StampResult.NotPresent, statement = StatementReadResult.Absent)
        val result =
            evaluate(
                artifact = unsigned,
                emulator = true,
                level = LightTrustFilterLevel.AllowLightSignedApks,
            )
        assertEquals(LightInstallDecision.Allow, result.decision)
        assertFalse(result.approvedByBundle)
        assertNotEquals(
            LightInstallDecision.Allow,
            evaluate(artifact = unsigned, emulator = true).decision,
        )
        val blocked =
            state.copy(
                bundle =
                    bundle.copy(
                        block =
                            listOf(
                                TrustBlock(
                                    BlockMatch.SignerSha256(signer),
                                    BlockAction.Block,
                                    "blocked",
                                )
                            )
                    )
            )
        assertIs<LightInstallDecision.Kill>(
            evaluate(
                    artifact = unsigned,
                    emulator = true,
                    level = LightTrustFilterLevel.AllowLightSignedApks,
                    state = blocked,
                )
                .decision
        )
    }

    @Test
    fun `malformed statement is distinguished and cannot grant approval`() {
        val result = evaluate(artifact = artifact.copy(statement = StatementReadResult.Malformed))
        assertEquals(LightInstallDecision.Deny(DenyReason.MalformedStatement), result.decision)
        assertFalse(result.approvedByBundle)
    }
}
