package com.thelightphone.sdk.trust

data class LightTrustTool(
    val id: String,
    val versionCode: Long,
    val versionName: String,
    val gitUrl: String,
    val gitCommit: String,
)

data class LightTrustStatement(
    val schemaVersion: Int,
    val tool: LightTrustTool,
    val sdkGitRef: String,
    val devId: String,
    val signerSha256: String,
    val buildId: String,
    val issuedAt: String,
)
