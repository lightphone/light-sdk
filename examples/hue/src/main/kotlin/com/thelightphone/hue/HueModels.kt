package com.thelightphone.hue

import kotlinx.serialization.Serializable

/** One entry from the meethue.com N-UPnP discovery endpoint. */
@Serializable
data class DiscoveredBridge(
    val id: String = "",
    val internalipaddress: String = "",
    val port: Int = 443,
)

/** Unauthenticated `/api/config` payload, used to learn the bridge id/name before pairing. */
@Serializable
data class HueConfig(
    val name: String = "",
    val bridgeid: String = "",
    val modelid: String = "",
    val swversion: String = "",
)

@Serializable
data class HueCreatedUser(
    val username: String = "",
    val clientkey: String = "",
)

@Serializable
data class HueApiError(
    val type: Int = 0,
    val address: String = "",
    val description: String = "",
)

/** Bridge responses are arrays of `{"success": ...}` / `{"error": ...}` items. */
@Serializable
data class HueCommandResult(
    val success: HueCreatedUser? = null,
    val error: HueApiError? = null,
)

@Serializable
data class HueLightState(
    val on: Boolean = false,
    val reachable: Boolean = true,
)

@Serializable
data class HueLight(
    val name: String = "",
    val state: HueLightState = HueLightState(),
)

/** Bridge error code returned when the link button has not been pressed yet. */
internal const val HUE_LINK_BUTTON_NOT_PRESSED = 101
