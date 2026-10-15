package com.example.ktorserver

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {
        embeddedServer(CIO, port = 8080) {}.start(wait = false)
    }

    override suspend fun onPushNotification(data: ByteArray) = Unit
}
