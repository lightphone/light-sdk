package com.thelightphone.sdk.server.toolmanager


import com.thelightphone.backup.RemoteAccessTokenProvider
import com.thelightphone.backup.RemoteBackupError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// Adapts OAuthTunnelClient.refreshToken's OAuthResult into the TokenRefreshOutcome shape
suspend fun OAuthTunnelClient.refreshAccessToken(refreshToken: String): TokenRefreshOutcome {
    val tokens = when (val result = refreshToken(refreshToken)) {
        is OAuthResult.Failure -> {
            return if (result.code == "invalid_grant") {
                TokenRefreshOutcome.InvalidGrant(result.error)
            } else {
                TokenRefreshOutcome.Failed(IllegalStateException(result.error))
            }
        }
        is OAuthResult.Success -> result.tokens.jsonObject
    }

    val accessToken = tokens["access_token"]?.jsonPrimitive?.contentOrNull
    // expires_in is in seconds per the OAuth2 spec (RFC 6749 4.2.2).
    val expiresInSeconds = tokens["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    if (accessToken == null || expiresInSeconds == null) {
        return TokenRefreshOutcome.Failed(IllegalStateException("missing or invalid refreshed token fields"))
    }

    return TokenRefreshOutcome.Refreshed(
        RefreshedAccessToken(
            accessToken,
            Clock.System.now() + expiresInSeconds.seconds
        )
    )
}

// Base class for token storage. Handles refreshing, just pass a lambda
class StoredOAuthTokenProvider(
    private val accountType: String,
    private val tokenStorage: TokenStorage,
    private val refresh: suspend (refreshToken: String) -> TokenRefreshOutcome,
) : RemoteAccessTokenProvider {
    // Refreshes are a network round trip plus a re-persist, and every concurrent call needs a
    // token - this mutex means concurrent callers during a refresh all await the one attempt
    // rather than each firing their own.
    private val mutex = Mutex()
    private var cached: StoredOAuthTokens? = null
    private var forceRefresh = false

    override suspend fun getAccessToken(): Result<String> = mutex.withLock {
        val stored = cached ?: tokenStorage.getOAuthDetails(accountType)
        ?: return@withLock Result.failure(
            RemoteBackupError.Unauthorized(IllegalStateException("no $accountType account linked")),
        )
        cached = stored

        val expiringSoon = Clock.System.now() >= stored.expiresAt - EXPIRY_SKEW
        if (!forceRefresh && !expiringSoon) {
            return@withLock Result.success(stored.accessToken)
        }

        when (val outcome = refresh(stored.refreshToken)) {
            is TokenRefreshOutcome.Refreshed -> {
                val updated = stored.copy(
                    accessToken = outcome.token.accessToken,
                    expiresAt = outcome.token.expiresAt,
                )
                tokenStorage.saveOAuthDetails(
                    accountType = accountType,
                    accessToken = updated.accessToken,
                    refreshToken = updated.refreshToken,
                    expiresAt = updated.expiresAt,
                    scope = updated.scope,
                )
                cached = updated
                forceRefresh = false
                Result.success(updated.accessToken)
            }
            is TokenRefreshOutcome.InvalidGrant -> {
                // The refresh token is permanently dead - keeping it around would just mean every
                // future call fails the same way, so drop it and let the caller detect "not linked".
                tokenStorage.removeOAuthDetails(accountType)
                cached = null
                Result.failure(RemoteBackupError.Unauthorized(IllegalStateException(outcome.message)))
            }
            is TokenRefreshOutcome.Failed -> {
                Result.failure(RemoteBackupError.Unauthorized(outcome.cause))
            }
        }
    }

    override suspend fun invalidateAccessToken() = mutex.withLock {
        forceRefresh = true
    }

    private companion object {
        val EXPIRY_SKEW = 2.minutes
    }
}