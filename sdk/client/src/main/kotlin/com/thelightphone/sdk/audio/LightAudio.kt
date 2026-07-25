package com.thelightphone.sdk.audio

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.thelightphone.sdk.SealedLightActivity

interface LightAudio {
    val capabilities: AudioCapabilities
    fun newPlayer(usage: LightAudioUsage = LightAudioUsage.Music): LightAudioPlayer
    /**
     * Create a player that adopts a caller-configured [ExoPlayer].
     *
     * The [configure] receiver provides an [ExoPlayer.Builder] (application
     * context) plus [LightExoPlayerConfigurer] helpers for media-source and
     * cache factories without importing Android `Context`. Return a fully built
     * `ExoPlayer` (source factory, cache, priority, load control, listeners).
     * The SDK re-asserts audio attributes, audio focus, and lifecycle after
     * adoption; do not manage focus, retain, or release the instance yourself.
     * For streams that cannot be a plain URL, resolve
     * [LightAudioSource.CustomSource] URIs via the factory on this player. A
     * custom media-source factory must still delegate file/asset/http(s)
     * schemes if those sources are used on the same player. Do not perform
     * network I/O on the main thread inside a media-source factory.
     */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun newPlayer(
        usage: LightAudioUsage = LightAudioUsage.Music,
        configure: LightExoPlayerConfigurer.() -> ExoPlayer,
    ): LightAudioPlayer
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

    /** Create a player that requests audio focus appropriate for [usage]. */
    override fun newPlayer(usage: LightAudioUsage): LightAudioPlayer {
        return LightAudioPlayer(sealedActivity.activity, usage)
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun newPlayer(
        usage: LightAudioUsage,
        configure: LightExoPlayerConfigurer.() -> ExoPlayer,
    ): LightAudioPlayer {
        return LightAudioPlayer(sealedActivity.activity, usage, configure)
    }

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
