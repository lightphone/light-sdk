package com.thelightphone.sample

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(
        serverData: StateFlow<LightServerData?>,
    ) {
        // This offline tool does not need server registration data.
    }

    override suspend fun onPushNotification(
        data: ByteArray,
    ) {
        // This offline tool does not use push notifications.
    }
}
