package com.thelightphone.sdk.trust

data class LightTrustBundle(
    val schemaVersion: Int,
    val version: Long,
    val issuedAt: String,
    val allow: List<TrustApproval>,
    val block: List<TrustBlock>,
    val trustedStampCerts: Set<String>,
    val revokedStampCerts: Set<String>,
)

data class TrustApproval(val toolId: String, val signerSha256: String, val minVersionCode: Long, val approvedArtifacts: List<ApprovedArtifact>)
sealed interface ApprovedArtifact {
    data class BuildId(val value: String) : ApprovedArtifact
    data class ApkSha256(val value: String) : ApprovedArtifact
}
enum class BlockAction { Block, Purge }
data class TrustBlock(val match: BlockMatch, val action: BlockAction, val reason: String)
sealed interface BlockMatch {
    data class SignerSha256(val value: String) : BlockMatch
    data class ToolId(val value: String) : BlockMatch
    data class ToolVersion(val toolId: String, val versionCode: Long) : BlockMatch
    data class ApkSha256(val value: String) : BlockMatch
}
