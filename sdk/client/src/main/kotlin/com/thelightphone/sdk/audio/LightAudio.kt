package com.thelightphone.sdk.audio

import android.content.Context
import android.media.AudioManager
import com.thelightphone.sdk.SealedLightActivity

interface LightAudio {
    val capabilities: AudioCapabilities
    fun newPlayer(
        usage: LightAudioUsage = LightAudioUsage.Music,
        playback: LightAudioPlayback = LightAudioPlayback.Attached,
        caches: List<LightMediaCache> = emptyList(),
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

    /**
     * Create a player that requests audio focus appropriate for [usage], reading
     * through [caches] in the order given before reaching the network.
     *
     * Only one [LightAudioPlayback.Detached] player handle may exist in the
     * process at a time. Reconnecting to a live detached session cannot change
     * where that session's player reads from, so [caches] must match the ones it
     * was built with.
     */
    override fun newPlayer(
        usage: LightAudioUsage,
        playback: LightAudioPlayback,
        caches: List<LightMediaCache>,
    ): LightAudioPlayer {
        validateMediaCaches(caches)
        if (playback == LightAudioPlayback.Attached) {
            return LightAudioPlayer(sealedActivity.activity, usage, playback, caches)
        }

        sealedActivity.activity.requireDetachedAudioCapability()
        val activeUsage = detachedSessionState.activeUsage()
        if (!isDetachedUsageCompatible(activeUsage, usage)) {
            throw LightAudioPlayerException(
                "Detached audio is already using $activeUsage; requested $usage. " +
                    "Reconnect with the active usage or wait for the detached session to stop."
            )
        }
        val activeCaches = detachedSessionState.activeCaches()
        if (!isDetachedCacheCompatible(activeCaches, caches)) {
            throw LightAudioPlayerException(
                "Detached audio is already reading through $activeCaches; requested $caches. " +
                    "Its player is already built, so reconnect with the active caches or " +
                    "stop the detached session first."
            )
        }
        if (!detachedSessionState.openHandle()) {
            throw LightAudioPlayerException(
                "Only one detached LightAudioPlayer may exist at a time; release the existing player first"
            )
        }
        // Staged before the controller is built, because building it is what
        // starts the service that reads this.
        detachedSessionState.stageCaches(caches)
        return try {
            LightAudioPlayer(
                sealedActivity.activity,
                usage,
                playback,
                caches,
                detachedSessionState::closeHandle,
            )
        } catch (error: Throwable) {
            detachedSessionState.closeHandle()
            throw error
        }
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
