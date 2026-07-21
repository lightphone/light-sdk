package com.thelightphone.sdk.audio

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Remember an audio factory scoped to the current Light activity composition.
 *
 * Create audio components from a screen's [androidx.compose.runtime.Composable]
 * content, then release the components when that screen is destroyed:
 * ```
 * val audio = rememberLightAudio()
 * val player = remember(audio) { audio.newPlayer() }
 * ```
 *
 * The factory itself does not need to be released.
 *
 * @throws IllegalStateException when called outside a Light activity composition.
 */
@Composable
fun rememberLightAudio(): LightAudio {
    val activity = checkNotNull(LocalActivity.current) {
        "rememberLightAudio must be called from a LightActivity-backed composition"
    }
    return remember(activity) {
        LightAudio(activity)
    }
}

/** Factory for creating audio components without exposing an Android context. */
class LightAudio internal constructor(
    private val activity: Activity
) {
    /** Current device output capabilities, read again on every access. */
    val capabilities: AudioCapabilities
        get() = activity.readAudioCapabilities()

    /** Create a player that requests audio focus appropriate for [usage]. */
    fun newPlayer(usage: LightAudioUsage = LightAudioUsage.Music): LightAudioPlayer {
        return LightAudioPlayer(activity, usage)
    }

    /** Create a recorder using [cfg]. Call [LightAudioRecorder.release] when done. */
    fun newRecorder(cfg: RecorderConfig = RecorderConfig()): LightAudioRecorder =
        LightAudioRecorder(activity, cfg)

    /** Create a microphone capture source using [cfg]. Collection owns its lifetime. */
    fun newCapture(cfg: CaptureConfig = CaptureConfig()): LightAudioCapture =
        LightAudioCapture(cfg)

    /**
     * Create one monophonic PCM voice at [sampleRate]. Generate or resample
     * buffers for that rate; use multiple voices when sounds must overlap.
     */
    fun newVoice(
        usage: LightAudioUsage = LightAudioUsage.Music,
        sampleRate: Int = capabilities.sampleRate,
    ): LightAudioVoice = LightAudioVoice(activity, usage, sampleRate)
}

private fun Context.readAudioCapabilities(): AudioCapabilities {
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val sampleRate = audioManager
        .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        ?.toIntOrNull()
        ?: DEFAULT_SAMPLE_RATE
    return AudioCapabilities(sampleRate = sampleRate)
}
