package com.thelightphone.sdk.server.toolmanager

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration.Companion.minutes

// CLIENT-SIDE FOR https://github.com/lightphone/light-oauth-relay

enum class TunnelMode {
    // Token exchange happens on the worker, mostly just for Google since they require client_secret for some scopes
    EXCHANGE,
    // Token exchange happens on device, here
    RELAY
}

sealed class OAuthResult {
    data class Success(val tokens: JsonElement) : OAuthResult()

    // `code` is the OAuth2 `error` field (RFC 6749 5.2) when the failure came from a parseable
    // provider/Worker error response - e.g. "invalid_grant" for a dead refresh token or
    // authorization code, "access_denied" for a declined consent screen. Null when the failure
    // happened before any such response existed (network error, timeout, unparsable body).
    data class Failure(val error: String, val code: String? = null) : OAuthResult()
}

private fun JsonElement.oauthErrorCode(): String? =
    (this as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull

@Serializable
private data class StartMessage(
    val type: String = "start",
    val mode: String,
    val provider: String? = null,
    val verifier: String? = null,
)

@Serializable
private data class TunnelResponse(
    val type: String,
    val tokens: JsonElement? = null,
    val code: String? = null,
    val error: JsonElement? = null,
)

@Serializable
private data class RefreshRequest(val provider: String, val refresh_token: String)

data class OAuthTunnelSpec(
    val sessionId: String,
    val verifier: String,
    val challenge: String,
    val authUrl: String,
    val mode: TunnelMode,
    val providerId: String? = null,     // required for EXCHANGE mode, must match the Worker's provider table key
    val tokenEndpoint: String? = null,  // required for RELAY mode, device exchanges the code itself
    val clientId: String? = null,       // required for RELAY mode
)

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

interface OAuthTunnelClient {
    fun newSpecForSessionId(sessionId: String): OAuthTunnelSpec
    suspend fun authenticate(spec: OAuthTunnelSpec): OAuthResult

    suspend fun refreshToken(refreshToken: String): OAuthResult
}

abstract class BaseOAuthTunnelClient(private val workerHost: String) : OAuthTunnelClient {
    private val redirectUri = "https://$workerHost/callback"
    private val client = HttpClient(OkHttp) { install(WebSockets) }

    abstract val mode: TunnelMode
    abstract val clientId: String
    abstract val authEndpoint: String
    abstract val scope: String

    /** Required for EXCHANGE mode, must match a key in the Worker's provider table. */
    open val providerId: String? = null

    /** Required for RELAY mode, the provider's token endpoint the device will POST to directly. */
    open val tokenEndpoint: String? = null

    open fun buildAuthUrl(sessionId: String, challenge: String): String {
        return authEndpoint +
                "?client_id=${enc(clientId)}" +
                "&redirect_uri=${enc(redirectUri)}" +
                "&response_type=code" +
                "&scope=${enc(scope)}" +
                "&state=${enc(sessionId)}" +
                "&code_challenge=${enc(challenge)}" +
                "&code_challenge_method=S256"
    }

    override fun newSpecForSessionId(sessionId: String): OAuthTunnelSpec {
        val verifier = randomUrlSafeString(32)
        val challenge = codeChallenge(verifier)
        val url = buildAuthUrl(sessionId, challenge)
        return OAuthTunnelSpec(
            sessionId = sessionId,
            verifier = verifier,
            challenge = challenge,
            authUrl = url,
            mode = mode,
            providerId = providerId,
            tokenEndpoint = tokenEndpoint,
            clientId = clientId,
        )
    }

    override suspend fun authenticate(spec: OAuthTunnelSpec): OAuthResult {
        return withTimeoutOrNull(5.minutes) {
            var result: OAuthResult? = null

            client.webSocket(
                urlString = "wss://$workerHost/tunnel/${spec.sessionId}",
            ) {
                val start = when (spec.mode) {
                    TunnelMode.EXCHANGE -> StartMessage(
                        mode = "exchange",
                        provider = spec.providerId,
                        verifier = spec.verifier,
                    )
                    TunnelMode.RELAY -> StartMessage(mode = "relay")
                }
                send(Frame.Text(json.encodeToString(start)))

                val frame = incoming.receiveCatching().getOrNull()
                if (frame is Frame.Text) {
                    val response = json.decodeFromString<TunnelResponse>(frame.readText())
                    result = when (response.type) {
                        "tokens" -> OAuthResult.Success(response.tokens!!)
                        "code" -> exchangeCodeDirectly(spec, response.code!!)
                        else -> OAuthResult.Failure(
                            response.error?.toString() ?: "unknown error",
                            code = response.error?.oauthErrorCode(),
                        )
                    }
                }
                close()
            }

            result
        } ?: OAuthResult.Failure("timed out waiting for authorization")
    }

    private suspend fun exchangeCodeDirectly(spec: OAuthTunnelSpec, code: String): OAuthResult {
        return try {
            val response = client.post(spec.tokenEndpoint!!) {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("client_id", spec.clientId!!)
                            append("code", code)
                            append("code_verifier", spec.verifier)
                            append("grant_type", "authorization_code")
                            append("redirect_uri", redirectUri)
                        },
                    ),
                )
            }
            val body = json.parseToJsonElement(response.bodyAsText())
            if (response.status.isSuccess()) {
                OAuthResult.Success(body)
            } else {
                OAuthResult.Failure(body.toString(), code = body.oauthErrorCode())
            }
        } catch (e: Exception) {
            OAuthResult.Failure("token exchange failed: ${e.message}")
        }
    }

    override suspend fun refreshToken(refreshToken: String): OAuthResult = when (mode) {
        TunnelMode.EXCHANGE -> refreshViaWorker(refreshToken)
        TunnelMode.RELAY -> refreshDirectly(refreshToken)
    }

    private suspend fun refreshViaWorker(refreshToken: String): OAuthResult {
        val provider = providerId
            ?: return OAuthResult.Failure("refresh failed: no providerId configured for EXCHANGE mode")
        return try {
            val response = client.post("https://$workerHost/refresh") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RefreshRequest(provider, refreshToken)))
            }
            val body = json.parseToJsonElement(response.bodyAsText())
            if (response.status.isSuccess()) {
                OAuthResult.Success(body)
            } else {
                OAuthResult.Failure(body.toString(), code = body.oauthErrorCode())
            }
        } catch (e: Exception) {
            OAuthResult.Failure("refresh failed: ${e.message}")
        }
    }

    private suspend fun refreshDirectly(refreshToken: String): OAuthResult {
        val endpoint = tokenEndpoint
            ?: return OAuthResult.Failure("refresh failed: no tokenEndpoint configured for RELAY mode")
        return try {
            val response = client.post(endpoint) {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("client_id", clientId)
                            append("refresh_token", refreshToken)
                            append("grant_type", "refresh_token")
                        },
                    ),
                )
            }
            val body = json.parseToJsonElement(response.bodyAsText())
            if (response.status.isSuccess()) {
                OAuthResult.Success(body)
            } else {
                OAuthResult.Failure(body.toString(), code = body.oauthErrorCode())
            }
        } catch (e: Exception) {
            OAuthResult.Failure("refresh failed: ${e.message}")
        }
    }
}

class GoogleOAuthTunnelClient(override val clientId: String, workerHost: String) : BaseOAuthTunnelClient(workerHost) {
    override val mode = TunnelMode.EXCHANGE
    override val providerId = "google"
    override val authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
    override val scope = "https://www.googleapis.com/auth/drive.file"

    override fun buildAuthUrl(sessionId: String, challenge: String): String {
        return super.buildAuthUrl(sessionId, challenge) + "&access_type=offline&prompt=consent"
    }
}

// TODO
class OneDriveOAuthTunnelClient(override val clientId: String, workerHost: String)  : BaseOAuthTunnelClient(workerHost) {
    override val mode = TunnelMode.RELAY
    override val authEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
    override val tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    override val scope = "offline_access Files.ReadWrite"
}

class DropboxOAuthTunnelClient(override val clientId: String, workerHost: String)  : BaseOAuthTunnelClient(workerHost) {
    override val mode = TunnelMode.RELAY
    override val authEndpoint = "https://www.dropbox.com/oauth2/authorize"
    override val tokenEndpoint = "https://api.dropboxapi.com/oauth2/token"
    override val scope = "files.content.write files.content.read"

    override fun buildAuthUrl(sessionId: String, challenge: String): String {
        return super.buildAuthUrl(sessionId, challenge) + "&token_access_type=offline"
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun randomUrlSafeString(byteLength: Int): String {
    val bytes = ByteArray(byteLength)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun codeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
