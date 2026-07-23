package com.thelightphone.hue

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Thrown when the bridge reports that the physical link button has not been pressed yet. */
internal class LinkButtonNotPressedException : Exception("Press the link button on your Hue bridge.")

/** Thrown when the stored application key is no longer accepted by the bridge. */
internal class UnauthorizedException : Exception("This bridge no longer recognizes the tool.")

@Serializable
private data class CreateUserRequest(
    val devicetype: String,
    val generateclientkey: Boolean = true,
)

@Serializable
private data class OnStateRequest(val on: Boolean)

internal class HueApi {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Public HTTPS with a normal CA-signed cert — uses the platform trust store.
    private val cloudClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
    }

    // Local bridge HTTPS — trust is pinned to the Hue bridge root CAs.
    private val bridgeClient = HttpClient(OkHttp) {
        engine { preconfigured = HueBridgeTls.bridgeClient() }
        install(ContentNegotiation) { json(json) }
    }

    suspend fun discoverBridges(): Result<List<DiscoveredBridge>> = runCatching {
        cloudClient.get("https://discovery.meethue.com/")
            .body<List<DiscoveredBridge>>()
            .filter { it.internalipaddress.isNotBlank() }
    }

    suspend fun fetchConfig(ip: String): Result<HueConfig> = runCatching {
        bridgeClient.get("https://$ip/api/config").body<HueConfig>()
    }

    /** Attempts to register with the bridge. Requires the link button to have been pressed. */
    suspend fun pair(ip: String): Result<HueCreatedUser> = runCatching {
        val body = bridgeClient.post("https://$ip/api") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateUserRequest(
                    devicetype = "light_phone_3#hue tool",
                    generateclientkey = true,
                ),
            )
        }.bodyAsText()

        val results = json.decodeFromString<List<HueCommandResult>>(body)
        val created = results.firstNotNullOfOrNull { it.success }
        if (created != null && created.username.isNotBlank()) {
            return@runCatching created
        }
        val error = results.firstNotNullOfOrNull { it.error }
        if (error?.type == HUE_LINK_BUTTON_NOT_PRESSED) {
            throw LinkButtonNotPressedException()
        }
        throw IllegalStateException(error?.description?.ifBlank { null } ?: "Unable to pair with bridge.")
    }

    suspend fun listLights(ip: String, appKey: String): Result<List<UiLight>> = runCatching {
        val body = bridgeClient.get("https://$ip/api/$appKey/lights").bodyAsText()
        ensureAuthorized(body)
        json.decodeFromString<Map<String, HueLight>>(body)
            .map { (id, light) ->
                UiLight(
                    id = id,
                    name = light.name.ifBlank { "Light $id" },
                    on = light.state.on,
                    reachable = light.state.reachable,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun setLightOn(ip: String, appKey: String, lightId: String, on: Boolean): Result<Unit> =
        runCatching {
            val body = bridgeClient.put("https://$ip/api/$appKey/lights/$lightId/state") {
                contentType(ContentType.Application.Json)
                setBody(OnStateRequest(on))
            }.bodyAsText()
            throwOnCommandError(body)
        }

    /** Group 0 is the special "all lights" group on every bridge. */
    suspend fun setAllLightsOn(ip: String, appKey: String, on: Boolean): Result<Unit> = runCatching {
        val body = bridgeClient.put("https://$ip/api/$appKey/groups/0/action") {
            contentType(ContentType.Application.Json)
            setBody(OnStateRequest(on))
        }.bodyAsText()
        throwOnCommandError(body)
    }

    private fun ensureAuthorized(body: String) {
        if (!body.trimStart().startsWith("[")) return
        val results = runCatching { json.decodeFromString<List<HueCommandResult>>(body) }.getOrNull()
        val error = results?.firstNotNullOfOrNull { it.error }
        if (error != null) throw UnauthorizedException()
    }

    private fun throwOnCommandError(body: String) {
        val results = runCatching { json.decodeFromString<List<HueCommandResult>>(body) }.getOrNull()
            ?: return
        val error = results.firstNotNullOfOrNull { it.error } ?: return
        if (error.type == HUE_LINK_BUTTON_NOT_PRESSED) throw LinkButtonNotPressedException()
        if (error.description.contains("unauthorized", ignoreCase = true)) throw UnauthorizedException()
        throw IllegalStateException(error.description.ifBlank { "Bridge rejected the command." })
    }

    fun close() {
        cloudClient.close()
        bridgeClient.close()
    }
}

data class UiLight(
    val id: String,
    val name: String,
    val on: Boolean,
    val reachable: Boolean,
)
