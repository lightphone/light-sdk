package com.thelightphone.sdk.trust

import com.thelightphone.sdk.trust.LightInstallDecision.Allow
import com.thelightphone.sdk.trust.LightInstallDecision.Deny
import com.thelightphone.sdk.trust.LightInstallDecision.Kill
import com.thelightphone.sdk.trust.LightTrustFilterLevel.AllowAllApks
import com.thelightphone.sdk.trust.LightTrustFilterLevel.AllowLightApprovedApks
import com.thelightphone.sdk.trust.LightTrustFilterLevel.AllowLightSignedApks
import com.thelightphone.sdk.trust.LightTrustFilterLevel.ExcludeAllApks
import kotlin.test.*

class LightInstallPolicyTest {
    private val appKey = "a".repeat(64)
    private val attackerKey = "b".repeat(64)
    private val stampCert = "c".repeat(64)
    private val apkHash = "d".repeat(64)
    private val otherApkHash = "e".repeat(64)
    private val toolId = "com.example.tool"
    private val buildId = "build_01H"
    private val verified = StampResult.Verified(stampCert, emptyList())

    private fun statement(
        id: String = toolId,
        versionCode: Long = 7,
        signerSha256: String = appKey,
        buildId: String = this.buildId,
    ) = LightTrustStatement(
        1, LightTrustTool(id, versionCode, "1.0", "https://example.com/repo", "abc"),
        "v1", "dev", signerSha256, buildId, "2026-08-25T00:00:00Z",
    )

    private fun approval(
        toolId: String = this.toolId,
        signerSha256: String = appKey,
        minVersionCode: Long = 7,
        artifacts: List<ApprovedArtifact> = listOf(ApprovedArtifact.BuildId(buildId)),
    ) = TrustApproval(toolId, signerSha256, minVersionCode, artifacts)

    private fun bundle(
        allow: List<TrustApproval> = listOf(approval()),
        block: List<TrustBlock> = emptyList(),
    ) = LightTrustBundle(1, 42, "2026-08-25T00:00:00Z", allow, block, setOf(stampCert), emptySet())

    /**
     * The default [apkSha256] fails the test if it is called, so every case that
     * does not deliberately pass one also asserts the lambda stayed unevaluated.
     * That laziness is the only reason the parameter is a lambda: as a `String` it
     * would make every caller hash the APK, and a refactor to one would look free.
     */
    private fun decide(
        stamp: StampResult = verified,
        statement: LightTrustStatement? = statement(),
        signerSha256: String = appKey,
        apkSha256: () -> String = { fail("hashed the APK when nothing needed the hash") },
        manifestVersionCode: Long = 7,
        bundle: LightTrustBundle? = bundle(),
        level: LightTrustFilterLevel = AllowLightApprovedApks,
    ) = LightInstallPolicy.decide(stamp, statement, signerSha256, apkSha256, manifestVersionCode, bundle, level)

    private class Case(val attack: String, val expected: LightInstallDecision, val actual: () -> LightInstallDecision)

    @Test fun `threat matrix`() {
        val blocked = bundle(block = listOf(TrustBlock(BlockMatch.ToolId(toolId), BlockAction.Purge, "compromised")))
        val cases = buildList {
            // A self-signed developer build carries no stamp and no statement.
            for (level in listOf(ExcludeAllApks, AllowLightApprovedApks, AllowLightSignedApks)) {
                add(Case("self-signed dev APK at $level", Deny(DenyReason.NotLightBuilt)) {
                    decide(stamp = StampResult.NotPresent, statement = null, level = level)
                })
            }
            add(Case("self-signed dev APK at $AllowAllApks", Allow) {
                decide(stamp = StampResult.NotPresent, statement = null, level = AllowAllApks)
            })

            // Editing the statement and re-zipping breaks the v2/v3 digests the stamp covers.
            add(Case("statement edited after signing", Deny(DenyReason.BadAttestation)) {
                decide(stamp = StampResult.NotVerified)
            })

            // Re-signing to repair those digests replaces the stamp with one Light did not issue.
            add(Case("statement edited, then re-signed with an attacker key", Deny(DenyReason.BadAttestation)) {
                decide(stamp = StampResult.NotVerified, signerSha256 = attackerKey)
            })

            // The stamp binds to APK content, so a transplanted statement cannot verify.
            add(Case("statement transplanted into another APK", Deny(DenyReason.BadAttestation)) {
                decide(stamp = StampResult.NotVerified, signerSha256 = attackerKey)
            })

            // Were a transplant ever to survive the stamp, the signer cross-check still catches it.
            add(Case("transplanted statement names another app key", Deny(DenyReason.StatementNotForThisApk)) {
                decide(signerSha256 = attackerKey)
            })

            // Light-built but never approved: the level is the whole difference.
            add(Case("unapproved tool where approval is required", Deny(DenyReason.NotApproved)) {
                decide(bundle = bundle(allow = emptyList()))
            })
            add(Case("unapproved tool where a stamp is enough", Allow) {
                decide(bundle = bundle(allow = emptyList()), level = AllowLightSignedApks)
            })

            // An append-only artifact list would keep every release ever approved installable.
            add(Case("fresh install of a superseded approved release", Deny(DenyReason.BelowMinVersion)) {
                decide(bundle = bundle(allow = listOf(approval(minVersionCode = 9))))
            })

            // A repack that edited one versionCode and forgot the other.
            add(Case("statement and manifest versionCode disagree", Deny(DenyReason.VersionCodeMismatch)) {
                decide(manifestVersionCode = 8)
            })

            // A second app key issued for a toolId that already has an owner.
            add(Case("duplicate app key for an existing toolId", Deny(DenyReason.SignerNotApprovedForTool)) {
                decide(statement = statement(signerSha256 = attackerKey), signerSha256 = attackerKey)
            })

            // Boundaries the threat table does not name.
            for ((level, expected) in listOf(
                ExcludeAllApks to Deny(DenyReason.FilteredOut),
                AllowLightApprovedApks to Deny(DenyReason.NotApproved),
                AllowLightSignedApks to Allow,
                AllowAllApks to Allow,
            )) {
                add(Case("device has never seen a bundle, at $level", expected) {
                    decide(bundle = null, level = level)
                })
            }
            // Provenance is answered before the statement is looked at, so a broken
            // stamp reports the stamp rather than the missing statement behind it.
            add(Case("unverifiable stamp with no statement either", Deny(DenyReason.BadAttestation)) {
                decide(stamp = StampResult.Unavailable("no verifier"), statement = null)
            })
            add(Case("toolId absent from allow", Deny(DenyReason.NotApproved)) {
                decide(bundle = bundle(allow = listOf(approval(toolId = "com.other.tool"))))
            })
            add(Case("versionCode exactly at the floor", Allow) {
                decide(bundle = bundle(allow = listOf(approval(minVersionCode = 7))))
            })
            add(Case("versionCode one below the floor", Deny(DenyReason.BelowMinVersion)) {
                decide(bundle = bundle(allow = listOf(approval(minVersionCode = 8))))
            })
            for (level in listOf(AllowLightApprovedApks, AllowLightSignedApks)) {
                add(Case("block and allow both match the same tool, at $level", Kill(BlockAction.Purge, "compromised")) {
                    decide(bundle = blocked, level = level)
                })
            }
            // The only block kind that has to pay for its own match.
            add(Case("block matching on the APK hash alone", Kill(BlockAction.Purge, "hashed")) {
                decide(
                    apkSha256 = { apkHash },
                    bundle = bundle(block = listOf(TrustBlock(BlockMatch.ApkSha256(apkHash), BlockAction.Purge, "hashed"))),
                )
            })
            add(Case("a fully approved tool at the most restrictive level", Deny(DenyReason.FilteredOut)) {
                decide(level = ExcludeAllApks)
            })
        }

        for (case in cases) assertEquals(case.expected, case.actual(), case.attack)
    }

    @Test fun `approval is read from the bundle, never from the statement`() {
        val text = """{"schemaVersion":1,"tool":{"id":"$toolId","versionCode":7,"versionName":"1.0",""" +
            """"gitUrl":"https://example.com/repo","gitCommit":"abc"},"sdkGitRef":"v1","devId":"dev",""" +
            """"signerSha256":"$appKey","buildId":"$buildId","issuedAt":"2026-08-25T00:00:00Z"}"""
        fun parse(json: String) = assertIs<TrustResult.Success<LightTrustStatement>>(
            LightTrustStatementParser.parse(json.encodeToByteArray())
        ).value
        val forged = parse(text.dropLast(1) + ""","approved":true}""")

        // The forged field changes nothing: unapproved stays denied, approved stays allowed.
        assertEquals(Deny(DenyReason.NotApproved), decide(statement = forged, bundle = bundle(allow = emptyList())))
        assertEquals(decide(statement = parse(text)), decide(statement = forged))
    }

    @Test fun `an unverified statement can only make the decision stricter`() {
        // Block matching reads toolId before the stamp is checked. An attacker who
        // controls that field can therefore only match a block and kill their own
        // APK; approval is read after provenance, so it can never be reached this way.
        val claimed = statement(id = toolId, signerSha256 = attackerKey)
        for (level in listOf(ExcludeAllApks, AllowLightApprovedApks, AllowLightSignedApks)) {
            assertEquals(
                Deny(DenyReason.NotLightBuilt),
                decide(stamp = StampResult.NotPresent, statement = claimed, signerSha256 = attackerKey, level = level),
                "unstamped APK claiming an approved toolId at $level",
            )
        }

        // Dropping toolId to dodge a block buys nothing either: provenance still fails.
        val blocked = bundle(block = listOf(TrustBlock(BlockMatch.ToolId(toolId), BlockAction.Block, "compromised")))
        assertEquals(
            Deny(DenyReason.BadAttestation),
            decide(stamp = StampResult.NotVerified, statement = statement(id = "com.renamed.tool"), bundle = blocked),
        )
    }

    @Test fun `the permissive level reports what a stricter one would have done`() {
        val blocked = bundle(block = listOf(TrustBlock(BlockMatch.ToolId(toolId), BlockAction.Purge, "compromised")))
        assertEquals(Allow, decide(bundle = blocked, level = AllowAllApks))
        assertEquals(Kill(BlockAction.Purge, "compromised"), decide(bundle = blocked, level = AllowLightApprovedApks))
    }

    @Test fun `the APK is hashed only when a rule asks for its hash, and only once`() {
        var hashed = 0
        val hash = { hashed++; apkHash }

        // No apkSha256 anywhere: buildId settles it for free.
        assertEquals(Allow, decide(apkSha256 = hash))
        assertEquals(0, hashed)

        // A matching buildId settles it for free even where the list puts apkSha256 first,
        // which is what "cheapest-first" means: list order must not decide who pays.
        val both = bundle(allow = listOf(approval(artifacts = listOf(ApprovedArtifact.ApkSha256(apkHash), ApprovedArtifact.BuildId(buildId)))))
        assertEquals(Allow, decide(apkSha256 = hash, bundle = both))
        assertEquals(0, hashed)

        // The same for blocks: a free match is found however late it sits in the list.
        val mixedBlocks = bundle(block = listOf(
            TrustBlock(BlockMatch.ApkSha256(apkHash), BlockAction.Purge, "hashed"),
            TrustBlock(BlockMatch.SignerSha256(appKey), BlockAction.Block, "compromised"),
        ))
        assertEquals(Kill(BlockAction.Block, "compromised"), decide(apkSha256 = hash, bundle = mixedBlocks))
        assertEquals(0, hashed)

        // Only an apkSha256 entry left to try.
        val byHash = listOf(ApprovedArtifact.BuildId("build_other"), ApprovedArtifact.ApkSha256(apkHash))
        assertEquals(Allow, decide(apkSha256 = hash, bundle = bundle(allow = listOf(approval(artifacts = byHash)))))
        assertEquals(1, hashed)

        // A block matched on something free never reaches the hash.
        hashed = 0
        val freeBlock = bundle(block = listOf(TrustBlock(BlockMatch.SignerSha256(appKey), BlockAction.Block, "compromised")))
        assertEquals(Kill(BlockAction.Block, "compromised"), decide(apkSha256 = hash, bundle = freeBlock))
        assertEquals(0, hashed)

        // A block that only the hash can match still costs exactly one hash.
        hashed = 0
        val killedByHash = bundle(block = listOf(TrustBlock(BlockMatch.ApkSha256(apkHash), BlockAction.Purge, "hashed")))
        assertEquals(Kill(BlockAction.Purge, "hashed"), decide(apkSha256 = hash, bundle = killedByHash))
        assertEquals(1, hashed)

        // A block and an approval both needing the hash share the one result.
        hashed = 0
        val hashBlock = bundle(
            allow = listOf(approval(artifacts = byHash)),
            block = listOf(TrustBlock(BlockMatch.ApkSha256(otherApkHash), BlockAction.Block, "compromised")),
        )
        assertEquals(Allow, decide(apkSha256 = hash, bundle = hashBlock))
        assertEquals(1, hashed)
    }

    @Test fun `every input combination returns a decision`() {
        val stamps = listOf(verified, StampResult.NotPresent, StampResult.NotVerified, StampResult.Unavailable("no verifier"))
        val statements = listOf(null, statement(), statement(signerSha256 = attackerKey), statement(id = "com.bad.tool", versionCode = 0))
        val bundles = listOf(
            null,
            bundle(allow = emptyList()),
            bundle(),
            bundle(
                allow = listOf(approval(artifacts = listOf(ApprovedArtifact.ApkSha256(apkHash)))),
                block = listOf(
                    TrustBlock(BlockMatch.ToolId("com.bad.tool"), BlockAction.Purge, "compromised"),
                    TrustBlock(BlockMatch.ApkSha256(apkHash), BlockAction.Block, "compromised"),
                ),
            ),
        )
        for (stamp in stamps) for (statement in statements) for (bundle in bundles) {
            for (signer in listOf(appKey, attackerKey)) for (version in listOf(0L, 7L, Long.MAX_VALUE)) {
                for (level in LightTrustFilterLevel.entries) {
                    assertNotNull(
                        LightInstallPolicy.decide(stamp, statement, signer, { apkHash }, version, bundle, level),
                        "$stamp / $statement / $signer / $version / $bundle / $level",
                    )
                }
            }
        }
    }
}
