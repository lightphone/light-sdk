package com.thelightphone.sdk.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager

internal class AudioFocusHelper(
    context: Context,
    usage: LightAudioUsage,
    gainType: Int,
    onFocusChange: (Int) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val request = AudioFocusRequest.Builder(gainType)
        .setAudioAttributes(usage.toAudioAttributes())
        .setOnAudioFocusChangeListener(onFocusChange)
        .build()

    fun request(): Boolean =
        audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }
}
