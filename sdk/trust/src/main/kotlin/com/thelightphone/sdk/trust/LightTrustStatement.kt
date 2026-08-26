package com.thelightphone.sdk.trust

data class LightTrustTool(
    val id: String,
    val versionCode: Long,
    val versionName: String,
    val gitUrl: String,
    val gitCommit: String,
)

data class LightAttestation(
    val keyId: String,
    val alg: String,
    val sig: String,
)

data class LightTrustStatement(
    val schemaVersion: Int,
    val tool: LightTrustTool,
    val sdkGitRef: String,
    val devId: String,
    val signerSha256: String,
    val buildId: String,
    val unsignedSha256: String,
    val issuedAt: String,
    val attestation: LightAttestation,
)
