package com.thelightphone.sdk.trust

/**
 * Identity of the tool a [LightTrustStatement] describes. [id] and [versionCode] are what trust
 * decisions key off of (approval lookup, block matching, the manifest versionCode check); the rest
 * is provenance recorded for humans, not evaluated by policy.
 */
data class LightTrustTool(
    val id: String,
    val versionCode: Long,
    val versionName: String,
    val gitUrl: String,
    val gitCommit: String,
)

/**
 * The claim a Light build embeds in an APK (`META-INF/light-trust.json`), read by
 * [LightTrustStatementReader] once the APK's source stamp has verified.
 *
 * A statement only asserts "this is what the SDK built" — it does not itself confer approval or
 * trust. [LightTrustStatementParser] parses it structurally only; only a trust bundle's `allow`
 * entries (matched against [signerSha256] and [buildId]) can actually approve an install.
 */
data class LightTrustStatement(
    /** Always `1` today; parsing rejects anything else. */
    val schemaVersion: Int,
    val tool: LightTrustTool,
    /** SDK commit this tool was built against — informational, not trust-bearing. */
    val sdkGitRef: String,
    /** Developer identity recorded at build time — informational, not trust-bearing. */
    val devId: String,
    /**
     * Must match the APK's actual signing certificate, or [LightInstallPolicy] rejects the install
     * as [DenyReason.StatementNotForThisApk].
     */
    val signerSha256: String,
    /** Matched against a bundle approval's `ApprovedArtifact.BuildId` entries. */
    val buildId: String,
    /** ISO-8601 instant recorded at signing time; not currently checked for expiry. */
    val issuedAt: String,
)
