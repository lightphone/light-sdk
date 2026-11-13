import com.thelightphone.sdk.server.toolmanager.RefreshedAccessToken
import com.thelightphone.sdk.server.toolmanager.StoredOAuthTokenProvider
import com.thelightphone.sdk.server.toolmanager.StoredOAuthTokens
import com.thelightphone.sdk.server.toolmanager.TokenRefreshOutcome
import com.thelightphone.sdk.server.toolmanager.TokenStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// mostly LLM'ed
private class FakeGoogleTokenStorage(initial: StoredOAuthTokens? = null) : TokenStorage {
    var stored: StoredOAuthTokens? = initial
        private set
    var saveCount = 0
    var removeCount = 0

    override suspend fun saveOAuthDetails(
        accountType: String,
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
        scope: String,
    ) {
        saveCount++
        stored = StoredOAuthTokens(accessToken, refreshToken, expiresAt, scope)
    }

    override suspend fun getOAuthDetails(accountType: String): StoredOAuthTokens? = stored

    override suspend fun removeOAuthDetails(accountType: String): Boolean {
        removeCount++
        val existed = stored != null
        stored = null
        return existed
    }
}

private fun expiredTokens() = StoredOAuthTokens(
    accessToken = "stale-access",
    refreshToken = "refresh-1",
    expiresAt = Clock.System.now() - 1.minutes,
    scope = "scope-a",
)

class StoredOAuthTokenProviderTest {
    @Test
    fun expiredToken_refreshSucceeds_savesAndReturnsNewAccessToken() = runBlocking {
        val storage = FakeGoogleTokenStorage(expiredTokens())
        val provider = StoredOAuthTokenProvider("google", storage) { refreshToken ->
            assertEquals("refresh-1", refreshToken)
            TokenRefreshOutcome.Refreshed(
                RefreshedAccessToken(
                    "new-access",
                    Clock.System.now() + 60.minutes
                )
            )
        }

        val token = provider.getAccessToken().getOrThrow()

        assertEquals("new-access", token)
        assertEquals(1, storage.saveCount)
        assertEquals(0, storage.removeCount)
        assertEquals("new-access", storage.stored?.accessToken)
    }

    @Test
    fun expiredToken_refreshFailsTransiently_keepsStoredTokens() = runBlocking {
        val storage = FakeGoogleTokenStorage(expiredTokens())
        val provider = StoredOAuthTokenProvider("google", storage) {
            TokenRefreshOutcome.Failed(IllegalStateException("network blip"))
        }

        val result = provider.getAccessToken()

        assertTrue(result.isFailure)
        assertEquals(0, storage.removeCount)
        assertEquals("stale-access", storage.stored?.accessToken)
    }

    @Test
    fun expiredToken_refreshInvalidGrant_clearsStoredTokens() = runBlocking {
        val storage = FakeGoogleTokenStorage(expiredTokens())
        val provider = StoredOAuthTokenProvider("google", storage) {
            TokenRefreshOutcome.InvalidGrant("refresh token revoked")
        }

        val result = provider.getAccessToken()

        assertTrue(result.isFailure)
        assertEquals(1, storage.removeCount)
        assertNull(storage.stored)
    }

    @Test
    fun noAccountLinked_failsWithoutCallingRefresh() = runBlocking {
        val storage = FakeGoogleTokenStorage(initial = null)
        var refreshCalled = false
        val provider = StoredOAuthTokenProvider("google", storage) {
            refreshCalled = true
            TokenRefreshOutcome.Failed(IllegalStateException("should not be called"))
        }

        val result = provider.getAccessToken()

        assertTrue(result.isFailure)
        assertFalse(refreshCalled)
    }
}
