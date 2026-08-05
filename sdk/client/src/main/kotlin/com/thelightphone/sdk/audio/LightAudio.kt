package com.thelightphone.sdk.audio

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.thelightphone.sdk.SealedLightActivity

interface LightAudio {
    val capabilities: AudioCapabilities

    /**
     * Creates a player that requests audio focus appropriate for [usage].
     * When [configure] is non-null, the SDK builds an [ExoPlayer.Builder] and
     * invokes it before constructing the player.
     */
    @OptIn(UnstableApi::class)
    fun newPlayer(
        usage: LightAudioUsage = LightAudioUsage.Music,
        configure: LightPlayerConfigurator? = null,
    ): LightAudioPlayer

    /** Sandboxed media3 cache and data-source helpers for this tool process. */
    @OptIn(UnstableApi::class)
    fun mediaEnv(): LightMediaEnv

    fun newRecorder(cfg: RecorderConfig = RecorderConfig()): LightAudioRecorder
    fun newCapture(cfg: CaptureConfig = CaptureConfig()): LightAudioCapture
    fun newVoice(
        usage: LightAudioUsage = LightAudioUsage.Music,
        sampleRate: Int = capabilities.sampleRate,
    ): LightAudioVoice
}

/** Factory for creating audio components without exposing an Android context. */
@JvmInline
value class DefaultLightAudio(
    private val sealedActivity: SealedLightActivity
) : LightAudio {
    /** Current device output capabilities, read again on every access. */
    override val capabilities: AudioCapabilities
        get() = sealedActivity.activity.readAudioCapabilities()

    @OptIn(UnstableApi::class)
    override fun newPlayer(
        usage: LightAudioUsage,
        configure: LightPlayerConfigurator?,
    ): LightAudioPlayer {
        return LightAudioPlayer.create(sealedActivity.activity, usage, configure)
    }

    @OptIn(UnstableApi::class)
    override fun mediaEnv(): LightMediaEnv =
        LightMediaEnv.forContext(sealedActivity.activity)

    /** Create a recorder using [cfg]. Call [LightAudioRecorder.release] when done. */
    override fun newRecorder(cfg: RecorderConfig): LightAudioRecorder =
        LightAudioRecorder(sealedActivity.activity, cfg)

    /** Create a microphone capture source using [cfg]. Collection owns its lifetime. */
    override fun newCapture(cfg: CaptureConfig): LightAudioCapture =
        LightAudioCapture(cfg)

    /**
     * Create one monophonic PCM voice at [sampleRate]. Generate or resample
     * buffers for that rate; use multiple voices when sounds must overlap.
     */
    override fun newVoice(
        usage: LightAudioUsage,
        sampleRate: Int
    ): LightAudioVoice = LightAudioVoice(sealedActivity.activity, usage, sampleRate)
}

private fun Context.readAudioCapabilities(): AudioCapabilities {
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val sampleRate = audioManager
        .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        ?.toIntOrNull()
        ?: DEFAULT_SAMPLE_RATE
    return AudioCapabilities(sampleRate = sampleRate)
}
