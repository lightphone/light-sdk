package com.thelightphone.sdk.audio

import android.media.AudioAttributes
import android.media.MediaRecorder
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes as Media3AudioAttributes

/** Describes how the platform should classify, process, and prioritize audio. */
enum class LightAudioUsage {
    /**
     * Music or other continuous media. Maps to media usage with music content,
     * so playback follows media volume/focus policy and the platform may apply
     * music-oriented processing.
     */
    Music,
    /**
     * Spoken media such as podcasts or narration. Maps to media usage with
     * speech content: it shares media volume/focus policy with [Music], while
     * allowing the platform to select speech-oriented processing.
     */
    Speech,
    /**
     * Alarm or timer sonification. Maps to alarm usage, selecting alarm volume
     * and focus policy. Exact interruption behavior remains device-controlled.
     */
    Alarm,
    /**
     * Voice communication such as a VoIP call. Maps to voice-communication
     * usage with speech content, giving the platform a routing and processing
     * hint for conversational audio. Actual routing remains device-controlled.
     */
    VoiceCall
}

internal data class AudioAttributeSpec(
    val usage: Int,
    val contentType: Int
)

internal fun LightAudioUsage.toAudioAttributeSpec(): AudioAttributeSpec = when (this) {
    LightAudioUsage.Music -> AudioAttributeSpec(
        usage = AudioAttributes.USAGE_MEDIA,
        contentType = AudioAttributes.CONTENT_TYPE_MUSIC
    )

    LightAudioUsage.Speech -> AudioAttributeSpec(
        usage = AudioAttributes.USAGE_MEDIA,
        contentType = AudioAttributes.CONTENT_TYPE_SPEECH
    )

    LightAudioUsage.Alarm -> AudioAttributeSpec(
        usage = AudioAttributes.USAGE_ALARM,
        contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION
    )

    LightAudioUsage.VoiceCall -> AudioAttributeSpec(
        usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
        contentType = AudioAttributes.CONTENT_TYPE_SPEECH
    )
}

internal fun LightAudioUsage.toAudioAttributes(): AudioAttributes {
    val spec = toAudioAttributeSpec()
    return AudioAttributes.Builder()
        .setUsage(spec.usage)
        .setContentType(spec.contentType)
        .build()
}

internal fun LightAudioUsage.toMedia3AudioAttributes(): Media3AudioAttributes {
    val spec = when (this) {
        LightAudioUsage.Music -> C.USAGE_MEDIA to C.AUDIO_CONTENT_TYPE_MUSIC
        LightAudioUsage.Speech -> C.USAGE_MEDIA to C.AUDIO_CONTENT_TYPE_SPEECH
        LightAudioUsage.Alarm -> C.USAGE_ALARM to C.AUDIO_CONTENT_TYPE_SONIFICATION
        LightAudioUsage.VoiceCall -> C.USAGE_VOICE_COMMUNICATION to C.AUDIO_CONTENT_TYPE_SPEECH
    }
    return Media3AudioAttributes.Builder()
        .setUsage(spec.first)
        .setContentType(spec.second)
        .build()
}

/**
 * Recorder input and encoded-output configuration.
 *
 * @property source microphone processing mode
 * @property sampleRate requested input sample rate in Hz
 * @property channels requested channel count; use `1` for mono or `2` for stereo
 */
data class RecorderConfig(
    val source: MicSource = MicSource.Mic,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val channels: Int = 1
)

/** Selects processed or raw microphone input for recording and capture. */
enum class MicSource {
    /** Standard processed microphone input. */
    Mic,
    /** Raw input when supported by the device. */
    Unprocessed
}

internal fun MicSource.toMediaRecorderSource(): Int = when (this) {
    MicSource.Mic -> MediaRecorder.AudioSource.MIC
    MicSource.Unprocessed -> MediaRecorder.AudioSource.UNPROCESSED
}

/**
 * Raw microphone capture configuration.
 *
 * @property sampleRate requested PCM sample rate in Hz
 * @property bufferFrames preferred frames per emitted buffer. The platform
 *   minimum may increase the actual size
 * @property source microphone processing mode. [MicSource.Unprocessed] bypasses
 *   the platform DSP chain (AGC, noise suppression) but requires device support;
 *   [MicSource.Mic] is the always-available processed path
 */
data class CaptureConfig(
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val bufferFrames: Int = DEFAULT_FRAMES_PER_BUFFER,
    val source: MicSource = MicSource.Unprocessed
)

/**
 * Audio values reported by the current device.
 *
 * @property sampleRate native output sample rate in Hz
 */
data class AudioCapabilities(
    val sampleRate: Int
) {
    companion object {
        /** Conservative capabilities used when the platform reports no value. */
        val Default = AudioCapabilities(sampleRate = DEFAULT_SAMPLE_RATE)
    }
}

/**
 * Descriptive metadata associated with a player queue item.
 *
 * @property title item title shown by playback surfaces
 * @property artist optional artist or creator name
 * @property album optional collection or album name
 * @property durationMs optional expected duration in milliseconds; this does
 *   not replace the player's resolved [LightAudioPlayer.durationMs]
 */
data class LightMediaMetadata(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null
)

/** Location of audio that can be added to a [LightAudioPlayer] queue. */
sealed interface LightAudioSource {
    /** A [file] accessible to the application. */
    data class FileSource(val file: java.io.File) : LightAudioSource
    /** A bundled [assetPath] relative to the application's assets directory. */
    data class AssetSource(val assetPath: String) : LightAudioSource
    /** A local or remote [url] supported by the platform player. */
    data class UrlSource(val url: String) : LightAudioSource
}

/**
 * One player queue entry.
 *
 * @property source playable media location
 * @property metadata descriptive values forwarded to playback surfaces
 */
data class LightAudioItem(
    val source: LightAudioSource,
    val metadata: LightMediaMetadata,
)

internal const val DEFAULT_SAMPLE_RATE = 48_000
internal const val DEFAULT_FRAMES_PER_BUFFER = 256
