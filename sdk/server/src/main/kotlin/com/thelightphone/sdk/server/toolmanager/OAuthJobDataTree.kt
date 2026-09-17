package com.thelightphone.sdk.server.toolmanager

import com.thelightphone.toolmanager.datatree.JobResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class StoredOAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
    val scope: String,
)

interface TokenStorage {
    suspend fun saveOAuthDetails(
        accountType: String,
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
        scope: String
    )

    suspend fun getOAuthDetails(accountType: String): StoredOAuthTokens?

    suspend fun removeOAuthDetails(accountType: String): Boolean
}

class OAuthJobDataTree(
    private val accountType: String,
    private val oAuthTunnelClient: OAuthTunnelClient,
    private val tokenStorage: TokenStorage,
    jobScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : CoroutineJobDataTree<OAuthResult>(jobScope) {
    private val pendingSpecs = ConcurrentHashMap<String, OAuthTunnelSpec>()

    override fun prepareRedirectUrl(jobId: String, path: Path, params: Map<String, String>): String? {
        val spec = oAuthTunnelClient.newSpecForSessionId(jobId)
        pendingSpecs[jobId] = spec
        return spec.authUrl
    }

    override suspend fun runJob(jobId: String): OAuthResult {
        val spec = pendingSpecs.remove(jobId)
            ?: error("runJob($jobId) called without a prior prepareJob($jobId)")
        return oAuthTunnelClient.authenticate(spec)
    }

    override suspend fun consumeResult(result: OAuthResult): Result<JobResult> {
        val tokens = when (result) {
            is OAuthResult.Failure -> return Result.failure(IllegalArgumentException("OAuth tunnel failed: ${result.error}"))
            is OAuthResult.Success -> result.tokens.jsonObject
        }

        val accessToken = tokens["access_token"]?.jsonPrimitive?.contentOrNull
        val refreshToken = tokens["refresh_token"]?.jsonPrimitive?.contentOrNull
        // expires_in is in seconds per the OAuth2 spec (RFC 6749 4.2.2).
        val expiresInSeconds = tokens["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val scope = tokens["scope"]?.jsonPrimitive?.contentOrNull
        if (accessToken == null || refreshToken == null || expiresInSeconds == null || scope == null) {
            return Result.failure(IllegalArgumentException("missing or invalid OAuth token fields"))
        }

        tokenStorage.saveOAuthDetails(
            accountType = accountType,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = Clock.System.now() + expiresInSeconds.seconds,
            scope = scope,
        )

        return Result.success(JobResult(message = "Linked $accountType account."))
    }
}
