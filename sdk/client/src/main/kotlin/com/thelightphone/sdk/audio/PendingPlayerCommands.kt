package com.thelightphone.sdk.audio

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PendingPlayerCommands {
    private val pending = mutableListOf<(Player) -> Unit>()
    private val _availability = MutableStateFlow(LightAudioPlayerAvailability.Initializing)
    private var player: Player? = null
    private var released = false

    val availability: StateFlow<LightAudioPlayerAvailability> = _availability.asStateFlow()

    fun dispatch(command: (Player) -> Unit) {
        val readyPlayer = synchronized(this) {
            checkNotReleased()
            player?.also { return@synchronized it } ?: run {
                pending += command
                null
            }
        }
        readyPlayer?.let(command)
    }

    fun ready(player: Player) {
        while (true) {
            val commands = synchronized(this) {
                if (released) return
                if (pending.isEmpty()) {
                    this.player = player
                    _availability.value = LightAudioPlayerAvailability.Ready
                    return
                }
                pending.toList().also { pending.clear() }
            }
            commands.forEach { it(player) }
        }
    }

    fun requireActive() {
        synchronized(this) { checkNotReleased() }
    }

    fun release() {
        synchronized(this) {
            released = true
            player = null
            pending.clear()
            _availability.value = LightAudioPlayerAvailability.Released
        }
    }

    private fun checkNotReleased() {
        check(!released) { "LightAudioPlayer has been released" }
    }
}
