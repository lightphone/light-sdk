package com.thelightphone.sdk.trust

import com.thelightphone.sdk.trust.LightInstallDecision.Allow
import com.thelightphone.sdk.trust.LightInstallDecision.Deny
import com.thelightphone.sdk.trust.LightInstallDecision.Kill

/**
 * Local mirror of the Android-owned `ClientFilterLevel`, which this module cannot import:
 * `:sdk:server` is an Android library, and Gradle will not hand an `androidJvm` variant to
 * a `jvm` consumer. Subtask 07 also makes `:sdk:server` depend on this module, so the
 * reverse edge would be a cycle.
 *
 * The constants are named identically so the mapping in 07 stays a rename-free `when`.
 * Collapsing the two into one enum in `:sdk:shared` is deferred — it touches published API.
 */
enum class LightTrustFilterLevel {
    ExcludeAllApks,
    AllowLightApprovedApks,
    AllowLightSignedApks,
    AllowAllApks,
}

/** Closed so callers assert on *why* an APK was refused and render a stable UI key. */
enum class DenyReason {
    /** No Light stamp, or no trust statement: not something the SDK produced. */
    NotLightBuilt,

    /** A stamp that does not verify, or a verifier that could not run. */
    BadAttestation,

    /** The statement describes a different APK than the one being installed. */
    StatementNotForThisApk,

    /** The statement and the manifest disagree about `versionCode`. */
    VersionCodeMismatch,

    /** The level admits no third-party APKs at all. */
    FilteredOut,

    /** No approval for this tool, or none covering this artifact. */
    NotApproved,

    /** Superseded by the approval's version floor. */
    BelowMinVersion,

    /** The tool is approved, but under a different app key than this APK carries. */
    SignerNotApprovedForTool,
}

sealed interface LightInstallDecision {
    data object Allow : LightInstallDecision
    data class Deny(val reason: DenyReason) : LightInstallDecision
    data class Kill(val action: BlockAction, val reason: String) : LightInstallDecision
}

object LightInstallPolicy {
    /**
     * Pure method that answers "can this APK be installed?"
     *
     * `AllowAllApks` installs anything without consulting the rules.
     * To tell a user what a stricter level would have done with the same APK,
     * call this again with that level.
     * The reason is in the returned `Deny` or `Kill`.
     */
    fun decide(
            stamp: StampResult,
            statement: LightTrustStatement?,
            signerSha256: String,
            apkSha256: () -> String,
            manifestVersionCode: Long,
            bundle: LightTrustBundle?,
            level: LightTrustFilterLevel,
    ): LightInstallDecision {
        // hashing the APK is the most expensive computation in this method,
        // and it's used for both block and approve logic
        val lazyApkSha256 = lazy(LazyThreadSafetyMode.NONE, apkSha256)

        // allowing all apks bypasses every check
        if (level == LightTrustFilterLevel.AllowAllApks) {
            return Allow
        }

        // a blocked tool is killed however well-formed it is, so check it before anything.
        // `toolId` and `versionCode` come from a statement nothing has authenticated yet.
        // safe in one direction only: approval is read after the stamp check,
        // so a forged `toolId` can kill its own APK but can never buy an install
        blockMatching(bundle, statement, signerSha256, lazyApkSha256)?.let {
            return Kill(it.action, it.reason)
        }

        when (stamp) {
            is StampResult.Verified -> Unit
            // a self-signed build carries no stamp
            StampResult.NotPresent -> return Deny(DenyReason.NotLightBuilt)
            // edited after signing, re-signed by someone else, or unverifiable
            StampResult.NotVerified,
            is StampResult.Unavailable -> return Deny(DenyReason.BadAttestation)
        }

        // past this point the stamp has been verified so the statement can be trusted
        if (statement == null) {
            return Deny(DenyReason.NotLightBuilt)
        }

        // signerSha256 is the cert hash reported by Android,
        // and the hash baked in the statement should match it
        if (statement.signerSha256 != signerSha256) {
            return Deny(DenyReason.StatementNotForThisApk)
        }

        // checking if version in the statement matches the manifest
        if (statement.tool.versionCode != manifestVersionCode) {
            return Deny(DenyReason.VersionCodeMismatch)
        }

        return when (level) {
            LightTrustFilterLevel.AllowLightSignedApks -> Allow
            LightTrustFilterLevel.ExcludeAllApks -> Deny(DenyReason.FilteredOut)
            LightTrustFilterLevel.AllowLightApprovedApks ->
                    checkApproval(statement, signerSha256, lazyApkSha256, bundle)
            LightTrustFilterLevel.AllowAllApks -> Allow
        }
    }

    private fun checkApproval(
            statement: LightTrustStatement,
            signerSha256: String,
            apkSha256: Lazy<String>,
            bundle: LightTrustBundle?,
    ): LightInstallDecision {
        val approval = bundle?.allow?.firstOrNull { it.toolId == statement.tool.id }
        if (approval == null) {
            return Deny(DenyReason.NotApproved)
        }

        if (approval.signerSha256 != signerSha256) {
            return Deny(DenyReason.SignerNotApprovedForTool)
        }

        // a fresh install can't take a tool back to a superseded version
        if (statement.tool.versionCode < approval.minVersionCode) {
            return Deny(DenyReason.BelowMinVersion)
        }

        // `buildId` comes with the statement, so it is free to compare
        val artifacts = approval.approvedArtifacts
        if (artifacts.any { it is ApprovedArtifact.BuildId && it.value == statement.buildId }) {
            return Allow
        }

        // only now is the full apk hash worth paying for
        if (artifacts.any { it is ApprovedArtifact.ApkSha256 && it.value == apkSha256.value }) {
            return Allow
        }

        return Deny(DenyReason.NotApproved)
    }

    /**
     * Checks on the trust bundle if an apk is blocked.
     * The trust bundle can match on different parameters, and we just need one of them to match.
     **/
    private fun blockMatching(
            bundle: LightTrustBundle?,
            statement: LightTrustStatement?,
            signerSha256: String,
            apkSha256: Lazy<String>,
    ): TrustBlock? {
        val blocks = bundle?.block.orEmpty()
        val toolId = statement?.tool?.id
        val versionCode = statement?.tool?.versionCode

        val matchedOnIdentity =
                blocks.firstOrNull {
                    when (val match = it.match) {
                        // block all apks signed by a given key
                        is BlockMatch.SignerSha256 -> match.value == signerSha256
                        // block all versions of a tool
                        is BlockMatch.ToolId -> match.value == toolId
                        // block a specific version of a tool
                        is BlockMatch.ToolVersion ->
                                match.toolId == toolId && match.versionCode == versionCode
                        // match apk hash in a separate loop to avoid unnecessary hash computation
                        is BlockMatch.ApkSha256 -> false
                    }
                }
        if (matchedOnIdentity != null) {
            return matchedOnIdentity
        }

        // after all metadata matches are exhausted, try to match by apk hash
        return blocks.firstOrNull {
            it.match is BlockMatch.ApkSha256 && it.match.value == apkSha256.value
        }
    }
}
