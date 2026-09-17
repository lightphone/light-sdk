import com.thelightphone.sdk.server.toolmanager.OAuthJobDataTree
import com.thelightphone.sdk.server.toolmanager.OAuthResult
import com.thelightphone.sdk.server.toolmanager.OAuthTunnelClient
import com.thelightphone.sdk.server.toolmanager.OAuthTunnelSpec
import com.thelightphone.sdk.server.toolmanager.StoredOAuthTokens
import com.thelightphone.sdk.server.toolmanager.TokenStorage
import com.thelightphone.sdk.server.toolmanager.TunnelMode
import com.thelightphone.toolmanager.datatree.JobStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

// TESTS MOSTLY LLM'ed

// authenticate() suspends on `result` until the test completes it, standing in for the real
// implementation's wait on the websocket tunnel.
private class FakeOAuthTunnelClient : OAuthTunnelClient {
    val result = CompletableDeferred<OAuthResult>()

    override fun newSpecForSessionId(sessionId: String) = OAuthTunnelSpec(
        sessionId = sessionId,
        verifier = "verifier",
        challenge = "challenge",
        authUrl = "https://example.com/auth?state=$sessionId",
        mode = TunnelMode.EXCHANGE,
        providerId = "fake",
    )

    override suspend fun authenticate(spec: OAuthTunnelSpec): OAuthResult = result.await()

    override suspend fun refreshToken(refreshToken: String): OAuthResult =
        throw NotImplementedError("not exercised by these tests")
}

private class FakeTokenStorage : TokenStorage {
    data class Saved(
        val accountType: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Instant,
        val scope: String,
    )

    val saved = mutableListOf<Saved>()

    override suspend fun saveOAuthDetails(
        accountType: String,
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
        scope: String,
    ) {
        saved += Saved(accountType, accessToken, refreshToken, expiresAt, scope)
    }

    override suspend fun getOAuthDetails(accountType: String): StoredOAuthTokens? =
        saved.lastOrNull { it.accountType == accountType }
            ?.let { StoredOAuthTokens(it.accessToken, it.refreshToken, it.expiresAt, it.scope) }

    override suspend fun removeOAuthDetails(accountType: String): Boolean =
        saved.removeAll { it.accountType == accountType }
}

private fun tokensJson(
    accessToken: String = "access-123",
    refreshToken: String = "refresh-456",
    expiresIn: Long = 3600,
    scope: String = "scope-a scope-b",
) = buildJsonObject {
    put("access_token", JsonPrimitive(accessToken))
    put("refresh_token", JsonPrimitive(refreshToken))
    put("expires_in", JsonPrimitive(expiresIn))
    put("scope", JsonPrimitive(scope))
}

class OAuthJobDataTreeTest {
    private val path = Path.of("/oauth/google")

    // Dispatchers.Unconfined runs a launched coroutine synchronously up to its first suspension
    // point, and resumes it synchronously on whatever thread completes that suspension. Since
    // FakeOAuthTunnelClient only ever suspends on `result`, completing `result` from the test
    // thread runs the rest of the job (processResult -> completeJob) to completion before the
    // test's next line, with no dispatcher juggling needed.
    private fun newTree(tunnel: FakeOAuthTunnelClient, tokenStorage: FakeTokenStorage) =
        OAuthJobDataTree(
            accountType = "google",
            oAuthTunnelClient = tunnel,
            tokenStorage = tokenStorage,
            jobScope = CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun startJob_returnsAuthUrlAndIsRunningBeforeTunnelResolves() = runBlocking {
        val tree = newTree(FakeOAuthTunnelClient(), FakeTokenStorage())

        val started = tree.startJob(path, emptyMap(), "https://self") { it }.getOrThrow()

        assertTrue(started.redirectUrl!!.contains(started.jobId))
        assertEquals(JobStatus.Running, tree.getJobStatus(path, started.jobId))
    }

    @Test
    fun successfulTunnel_savesTokensAndReportsSucceeded() = runBlocking {
        val tunnel = FakeOAuthTunnelClient()
        val tokenStorage = FakeTokenStorage()
        val tree = newTree(tunnel, tokenStorage)

        val started = tree.startJob(path, emptyMap(), "https://self") { it }.getOrThrow()
        tunnel.result.complete(OAuthResult.Success(tokensJson()))

        assertIs<JobStatus.Succeeded>(tree.getJobStatus(path, started.jobId))
        val saved = tokenStorage.saved.single()
        assertEquals("google", saved.accountType)
        assertEquals("access-123", saved.accessToken)
        assertEquals("refresh-456", saved.refreshToken)
        assertEquals("scope-a scope-b", saved.scope)
    }

    @Test
    fun tunnelFailure_marksJobFailedWithoutSavingTokens() = runBlocking {
        val tunnel = FakeOAuthTunnelClient()
        val tokenStorage = FakeTokenStorage()
        val tree = newTree(tunnel, tokenStorage)

        val started = tree.startJob(path, emptyMap(), "https://self") { it }.getOrThrow()
        tunnel.result.complete(OAuthResult.Failure("access_denied"))

        val status = tree.getJobStatus(path, started.jobId)
        assertIs<JobStatus.Failed>(status)
        assertTrue(status.message.contains("access_denied"))
        assertTrue(tokenStorage.saved.isEmpty())
    }

    @Test
    fun missingTokenField_marksJobFailedWithoutSavingTokens() = runBlocking {
        val tunnel = FakeOAuthTunnelClient()
        val tokenStorage = FakeTokenStorage()
        val tree = newTree(tunnel, tokenStorage)

        val started = tree.startJob(path, emptyMap(), "https://self") { it }.getOrThrow()
        tunnel.result.complete(
            OAuthResult.Success(
                buildJsonObject {
                    put("access_token", JsonPrimitive("access-123"))
                    put("expires_in", JsonPrimitive(3600))
                    put("scope", JsonPrimitive("scope-a"))
                    // no refresh_token
                },
            ),
        )

        assertIs<JobStatus.Failed>(tree.getJobStatus(path, started.jobId))
        assertTrue(tokenStorage.saved.isEmpty())
    }

    @Test
    fun secondStartJobAtSamePath_cancelsFirstJob() = runBlocking {
        // Never completed, so the first job's authenticate() call hangs until cancelled.
        val tunnel = FakeOAuthTunnelClient()
        val tree = newTree(tunnel, FakeTokenStorage())

        val first = tree.startJob(path, emptyMap(), "https://self", { it }).getOrThrow()
        val second = tree.startJob(path, emptyMap(), "https://self", { it }).getOrThrow()

        assertIs<JobStatus.Failed>(tree.getJobStatus(path, first.jobId))
        assertEquals(JobStatus.Running, tree.getJobStatus(path, second.jobId))
    }
}
