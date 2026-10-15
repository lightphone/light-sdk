package com.thelightphone.sdk.audio

import android.content.Context
import android.media.AudioManager
import com.thelightphone.sdk.SealedLightActivity

data class LightPlayerSpec(
    val usage: LightAudioUsage = LightAudioUsage.Music,
    val playback: LightAudioPlayback = LightAudioPlayback.Attached,
    val cache: LightMediaCache? = null,
)

interface LightAudio {
    val capabilities: AudioCapabilities

    fun newPlayer(spec: LightPlayerSpec = LightPlayerSpec()): LightAudioPlayer
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
     * Create a player that requests audio focus appropriate for [spec], reading
     * through its cache, if any, before reaching the network.
     *
     * Only one [LightAudioPlayback.Detached] player handle may exist in the
     * process at a time. Reconnecting to a live detached session cannot change
     * its configuration, so [spec] must match the live session's specification.
     *
     * @throws IllegalArgumentException when [spec]'s cache name is already in
     *   use in this process with a different size
     */
    override fun newPlayer(spec: LightPlayerSpec): LightAudioPlayer {
        if (spec.playback == LightAudioPlayback.Attached) {
            spec.cache?.let(mediaCacheStore::claim)
            return LightAudioPlayer(sealedActivity.activity, spec)
        }

        sealedActivity.activity.requireDetachedAudioCapability()
        admitDetachedPlayer(spec)
        return try {
            LightAudioPlayer(
                sealedActivity.activity,
                spec,
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

/**
 * Takes the detached handle and stages [spec] for the service, or throws having
 * changed nothing. The cache is claimed first: the service opens it in
 * `onCreate`, where a size mismatch would crash the process instead.
 */
internal fun admitDetachedPlayer(
    spec: LightPlayerSpec,
    state: DetachedSessionState = detachedSessionState,
    claimCache: (LightMediaCache) -> Unit = mediaCacheStore::claim,
) {
    spec.cache?.let(claimCache)
    val activeSpec = state.activeSpec()
    if (!isDetachedSpecCompatible(activeSpec, spec)) {
        throw LightAudioPlayerException(
            "Detached audio is already using $activeSpec; requested $spec. " +
                "Reconnect with the active specification or stop the detached session first."
        )
    }
    if (!state.openHandle()) {
        throw LightAudioPlayerException(
            "Only one detached LightAudioPlayer may exist at a time; release the existing player first"
        )
    }
    state.stageSpec(spec)
}
