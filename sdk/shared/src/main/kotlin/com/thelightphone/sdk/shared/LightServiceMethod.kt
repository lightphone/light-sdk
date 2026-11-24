package com.thelightphone.sdk.shared

import com.thelightphone.sdk.shared.LightServiceMethod.SetRingtone.Request
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

val lightJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

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
            val enableKeyAnimation: Boolean,
            // optional for older sdk servers that omit this field
            val swipeEnabled: Boolean? = null,
        )
    }

    object GetUserPreferences : LightServiceMethod<Unit, GetUserPreferences.Response> {
        override val id = "GetUserPreferences"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Response(
            val hapticsEnabled: Boolean,
        )
    }

    object GetPermission : LightServiceMethod<GetPermission.Request, GetPermission.Response> {
        enum class Result {
            Unknown, BlockedByServer, Granted, Denied
        }
        override val id = "GetPermission"
        override val requestSerializer = serializer<Request>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Request(val permissionName: String)

        @Serializable
        data class Response(
            val permissionResult: Result
        )
    }

    object RequestPermissionComponent : LightServiceMethod<Unit, RequestPermissionComponent.Response> {
        const val PERMISSION_NAME_KEY = "PermissionName"
        override val id = "RequestPermissionComponent"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Response(val componentName: String)
    }

    object DeviceKeyEvent : LightServiceMethod<DeviceKeyEvent.Request, Unit> {
        override val id = "DeviceKeyEvent"
        override val requestSerializer = serializer<Request>()
        override val responseSerializer = serializer<Unit>()

        @Serializable
        data class Request(
            val keyCode: Int,
            val repeatCount: Int?,
            val action: Int, // Android KeyEvent actions
            val characters: String?,
            val unicodeChar: Int,
            // if this key event will trigger the server to take over the screen
            // optionally pass the flattened component to re-launch when it is done
            val componentToRelaunch: String?,
        )
    }

    object OpenDialer : LightServiceMethod<OpenDialer.Request, Unit> {
        override val id = "OpenDialer"
        override val requestSerializer = serializer<Request>()
        override val responseSerializer = serializer<Unit>()

        @Serializable
        data class Request(
            val phoneNumber: String,
        )
    }

    // Gets the latest cached device location as stored by LightOS
    // Requires location permission
    object GetCurrentLocation : LightServiceMethod<Unit, GetCurrentLocation.Response> {
        override val id = "GetCurrentLocation"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Response(
            val latitude: Double? = null,
            val longitude: Double? = null,
            val accuracyMeters: Double? = null,
            val timestampMs: Long? = null,
        )
    }

    // In LightOS, users are able to set a default location for tools like rideshare/directions
    // if a tool is granted permission, this can be used to query that
    object GetDefaultLocation : LightServiceMethod<Unit, GetDefaultLocation.Response> {
        override val id = "GetDefaultLocation"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Response(
            val latitude: Double? = null,
            val longitude: Double? = null,
        )
    }

    // Tells LightOS to start listening for location updates
    // as long as any one tool has requested this in the past ~1 minute, listening will stay active
    // Requires location permission
    object RequestLocationUpdates : LightServiceMethod<Unit, Unit> {
        override val id = "RequestLocationUpdates"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Unit>()
    }

    // Signals that this tool no longer needs LightOS to listen for location updates
    // If this was the only tool that had requested updates, LightOS will stop listening immediately
    // Requires location permission
    object ReleaseLocationUpdates : LightServiceMethod<Unit, Unit> {
        override val id = "ReleaseLocationUpdates"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Unit>()
    }

    // Gets the package of the app that handles full-screen text entry, see
    // com.thelightphone.lp3Keyboard.textentry.TextEntryProtocol. null if there isn't one.
    object GetKeyboardPackage : LightServiceMethod<Unit, GetKeyboardPackage.Response> {
        override val id = "GetKeyboardPackage"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Response(
            val packageName: String? = null,
        )
    }

    /**
     * Base type for tool-defined methods that are not part of [allMethods].
     * The server routes these through [com.thelightphone.sdk.server.LightSdkServer.customServiceMethodResolver].
     */
    abstract class CustomServiceMethod<Req, Res> : LightServiceMethod<Req, Res>
}

val allMethods: Map<String, LightServiceMethod<*, *>> = listOf(
    LightServiceMethod.GetToken,
    LightServiceMethod.GetVersion,
    LightServiceMethod.SetRingtone,
    LightServiceMethod.GetKeyboardOptions,
    LightServiceMethod.GetPermission,
    LightServiceMethod.RequestPermissionComponent,
    LightServiceMethod.DeviceKeyEvent,
    LightServiceMethod.GetUserPreferences,
    LightServiceMethod.OpenDialer,
    LightServiceMethod.GetCurrentLocation,
    LightServiceMethod.GetDefaultLocation,
    LightServiceMethod.RequestLocationUpdates,
    LightServiceMethod.ReleaseLocationUpdates,
    LightServiceMethod.GetKeyboardPackage,
).associateBy { it.id }
