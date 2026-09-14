package com.thelightphone.sdk.emulator.backup

import com.thelightphone.backup.BaseOAuthTunnelClient
import com.thelightphone.backup.TunnelMode
import com.thelightphone.sdk.emulator.http.EmulatorHttpServer

class EmulatorRelayOAuthTunnelClient(
    workerHost: String
) : BaseOAuthTunnelClient(workerHost) {

    private val emulatorBaseUrl = "http://127.0.0.1:${EmulatorHttpServer.PORT}"

    override val mode: TunnelMode = TunnelMode.RELAY
    override val clientId: String = "emulator-client"
    override val authEndpoint: String = "$emulatorBaseUrl/oauth/authorize"
    override val scope: String = "files"
    override val providerId: String = "emulator"
    override val tokenEndpoint: String = "$emulatorBaseUrl/oauth/token"
}
