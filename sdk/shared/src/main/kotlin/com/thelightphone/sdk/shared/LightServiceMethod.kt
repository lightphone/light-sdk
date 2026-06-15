package com.thelightphone.sdk.shared

import com.thelightphone.sdk.shared.LightServiceMethod.SetRingtone.Request
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

val lightJson = Json { ignoreUnknownKeys = true }

/**
 * Defines a typed method that a client can call on the server's bound service.
 */
sealed interface LightServiceMethod<TRequest, TResponse> {

    val id: String
    val requestSerializer: KSerializer<TRequest>
    val responseSerializer: KSerializer<TResponse>

    fun encodeRequest(request: TRequest): String =
        lightJson.encodeToString(requestSerializer, request)

    fun decodeRequest(json: String): TRequest =
        lightJson.decodeFromString(requestSerializer, json)

    fun encodeResponse(response: TResponse): String =
        lightJson.encodeToString(responseSerializer, response)

    fun decodeResponse(json: String): TResponse =
        lightJson.decodeFromString(responseSerializer, json)

    /**
     * Define all service methods below. DO NOT CHANGE EXISTING METHODS
     */
    object GetToken : LightServiceMethod<Unit, GetToken.Response> {
        override val id = "GetToken"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Response(val token: String)
    }

    object GetVersion : LightServiceMethod<Unit, GetVersion.Response> {
        override val id = "GetVersion"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Response(val version: String)
    }

    object SetRingtone : LightServiceMethod<Request, Unit> {
        override val id = "SetRingtone"
        override val requestSerializer = serializer<Request>()
        override val responseSerializer = serializer<Unit>()

        @Serializable
        data class Request(val type: Int, val uri: String)
    }

    object GetKeyboardOptions : LightServiceMethod<Unit, GetKeyboardOptions.Response> {
        override val id = "GetKeyboardOptions"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Response(
            // "😅😅😅😅😅😅" -> keyboard will parse out emoji code points
            val emojisAsString: String?,
            val displayVoice: Boolean,
            val enableKeyAnimation: Boolean
        )
    }
}

val allMethods: Map<String, LightServiceMethod<*, *>> = listOf(
    LightServiceMethod.GetToken,
    LightServiceMethod.GetVersion,
    LightServiceMethod.SetRingtone,
    LightServiceMethod.GetKeyboardOptions
).associateBy { it.id }
