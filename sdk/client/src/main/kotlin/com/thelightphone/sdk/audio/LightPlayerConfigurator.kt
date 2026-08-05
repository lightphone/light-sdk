package com.thelightphone.sdk.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Configures an [ExoPlayer.Builder] before the SDK builds the player.
 */
@UnstableApi
fun interface LightPlayerConfigurator {
    fun configure(builder: ExoPlayer.Builder, env: LightMediaEnv)
}
