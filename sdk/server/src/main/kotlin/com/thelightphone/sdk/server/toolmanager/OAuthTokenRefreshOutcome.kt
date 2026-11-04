package com.thelightphone.sdk.server.toolmanager

import kotlin.time.Instant

data class RefreshedAccessToken(val accessToken: String, val expiresAt: Instant)

sealed class TokenRefreshOutcome {
    data class Refreshed(val token: RefreshedAccessToken) : TokenRefreshOutcome()

    // The refresh token itself is dead (OAuth invalid_grant - revoked, expired, or the user pulled
    // access). Don't retry
    data class InvalidGrant(val message: String) : TokenRefreshOutcome()

    // Anything else - network failure, unexpected response shape, etc. Maybe retry
    data class Failed(val cause: Throwable) : TokenRefreshOutcome()
}