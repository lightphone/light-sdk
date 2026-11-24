package com.thelightphone.sdk.server.toolmanager

import com.thelightphone.toolmanager.TetherEndpoint
import kotlinx.coroutines.flow.Flow

/**
 * Device hooks for running the tool manager over USB tethering instead of Wi-Fi.
 * Turning tethering on/off needs platform APIs, so the host app implements this and
 * installs it via [com.thelightphone.sdk.server.LightSdkServer.provideUsbTethering].
 * The emulator should return null and thus runs Tool Manager on Wi-Fi, LightOS returns a real
 * implementation.
 */
interface UsbTethering {
    /** mDNS hostname to announce on the tether interface. (Shouldn't include .local) */
    val hostname: String

    /**
     * Requests USB tethering if it isn't already up (no-op if it is). Without a cable connected it
     * may only come up once one is. Suspends while getting ready to make the request; returns once
     * it's made, not once tethering is up (follow [link] for that).
     */
    suspend fun start()

    fun stop()

    /**
     * The USB link as the device sees it: the current state as soon as it's known, then each
     * change. Cold, so monitoring runs only while collected. [ToolManagerLifecycleWrapperService]
     * turns this into a [UsbTetherStatus].
     */
    val link: Flow<UsbTetherLink>
}

data class UsbTetherLink(
    /** Getting power over USB at all (computer, charger, or charge-only cable). */
    val powered: Boolean = false,
    /** A USB host has enumerated the phone (data connection). */
    val usbHostConnected: Boolean = false,
    /** The USB tether network interface is up. */
    val tetherInterfaceUp: Boolean = false,
    /** A computer has taken an address on the tether network. */
    val computerJoined: Boolean = false,
    /** Set when tethering couldn't be set up. */
    val error: UsbTetherStatus.Error? = null,
)

sealed class UsbTetherStatus {
    /** Nothing plugged in. */
    data object Disconnected : UsbTetherStatus()

    /** Getting power but no USB data */
    data object ChargingOnly : UsbTetherStatus()

    /** A USB host has enumerated the phone */
    data object UsbConnected : UsbTetherStatus()

    /**
     * Tethering is up, but the computer hasn't joined the tether network.
     */
    data object WaitingForComputer : UsbTetherStatus()

    /**
     * A computer is on the tether network and the tool manager is serving there.
     */
    data class ComputerConnected(val endpoint: TetherEndpoint) : UsbTetherStatus()

    /** USB tethering couldn't be set up. */
    data class Error(val code: Int, val message: String) : UsbTetherStatus()
}
