package com.thelightphone.sdk.audio

import android.media.AudioAttributes
import android.media.MediaRecorder
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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

/** Whether playback is tied to the tool screen or survives leaving it. */
enum class LightAudioPlayback {
    /** Playback stops when the player is released. */
    Attached,
    /** Playback continues after the player handle is released. */
    Detached,
}

/** What went wrong during playback, at the granularity a tool can act on. */
enum class LightAudioErrorKind {
    /** Network or file I/O failure. */
    Source,
    /** Container, codec, or decoding support failure. */
    Unsupported,
    /** Audio output initialization or write failure. */
    Output,
    /** Failure without a more actionable classification. */
    Unknown,
}

/** Current playback failure, using SDK-owned types shared by both playback modes. */
data class LightAudioError(
    /** Actionable failure category. */
    val kind: LightAudioErrorKind,
    /** Stable media3 error-code name for logs and bug reports. */
    val diagnostic: String,
    /** Queue index that failed, or `-1` when unavailable. Follows queue edits. */
    val itemIndex: Int,
    /** [LightQueueItem.id] of the item that failed, or `null` when unavailable. */
    val itemId: String? = null,
)

internal fun errorKindFor(errorCode: Int): LightAudioErrorKind = when (errorCode) {
    in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> LightAudioErrorKind.Source
    in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> LightAudioErrorKind.Unsupported
    in PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED..
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED -> LightAudioErrorKind.Output
    else -> LightAudioErrorKind.Unknown
}

/** media3 does not advance past a failed item, so the current item is the one that failed. */
internal fun PlaybackException.toLightAudioError(player: Player): LightAudioError = LightAudioError(
    kind = errorKindFor(errorCode),
    diagnostic = PlaybackException.getErrorCodeName(errorCode),
    itemIndex = if (player.mediaItemCount == 0) NO_MEDIA_ITEM else player.currentMediaItemIndex,
    itemId = player.currentMediaItem?.mediaId,
)

/** This error with [LightAudioError.itemIndex] moved to where its item now sits. */
internal fun LightAudioError.relocatedIn(player: Player): LightAudioError {
    val id = itemId ?: return this
    val index = when {
        player.mediaItemCount > 0 && player.currentMediaItem?.mediaId == id -> player.currentMediaItemIndex
        else -> (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == id }
            ?: NO_MEDIA_ITEM
    }
    return copy(itemIndex = index)
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
 * Session extras key LightOS reads from the platform session to pick its
 * now-playing layout. Part of the LightOS contract: do not rename.
 */
internal const val SESSION_USAGE_EXTRA = "com.thelightphone.audio.USAGE"

/** Value published under [SESSION_USAGE_EXTRA], or null when LightOS has no layout for it. */
internal fun LightAudioUsage.sessionUsageValue(): String? = when (this) {
    LightAudioUsage.Music -> "music"
    LightAudioUsage.Speech -> "speech"
    LightAudioUsage.Alarm, LightAudioUsage.VoiceCall -> null
}


/**
 * Player commands withheld from controllers other than the tool's own, so the
 * platform session advertises ±15s for speech and previous/next for music.
 */
internal fun LightAudioUsage.hiddenExternalPlayerCommands(): IntArray = when (this) {
    LightAudioUsage.Music -> intArrayOf(
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
    )
    LightAudioUsage.Speech -> intArrayOf(
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    )
    LightAudioUsage.Alarm, LightAudioUsage.VoiceCall -> intArrayOf()
}

/**
 * Seek-back and seek-forward step for every usage. Media3 only takes it at build
 * time and items can switch usage mid-queue; music hides seeking from external
 * controllers, so only speech surfaces it.
 */
internal const val SEEK_INCREMENT_MS = 15_000L

/** Media metadata extras key carrying a queue item's own usage to the player holding it. */
internal const val ITEM_USAGE_EXTRA = "com.thelightphone.sdk.audio.ITEM_USAGE"

/** Whether a queue item may set this usage; the others only make sense for a whole player. */
internal fun LightAudioUsage.isItemUsage(): Boolean =
    this == LightAudioUsage.Music || this == LightAudioUsage.Speech

/** The usage an item plays with: its own, else the player's. */
internal fun effectiveUsage(itemUsage: LightAudioUsage?, playerUsage: LightAudioUsage): LightAudioUsage =
    itemUsage ?: playerUsage

/** Reads back a usage written under [ITEM_USAGE_EXTRA], or null when absent or unknown. */
internal fun itemUsageFromExtra(value: String?): LightAudioUsage? =
    LightAudioUsage.entries.firstOrNull { it.name == value }

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
 * @property id stable identity and cache key, globally unique for each distinct
 *   piece of audio and stable over time. Defaults to the source URL; rotating
 *   signed URLs need an explicit id to reuse cached bytes.
 * @property usage how this item plays while it is current, overriding the
 *   player's [LightPlayerSpec.usage]: audio processing, the lock screen
 *   controls (previous/next for [LightAudioUsage.Music], ±15s for
 *   [LightAudioUsage.Speech]) and the usage LightOS reads. Only
 *   [LightAudioUsage.Music] and [LightAudioUsage.Speech]; null follows the player.
 */
data class LightAudioItem(
    val source: LightAudioSource,
    val metadata: LightMediaMetadata,
    val id: String? = null,
    val usage: LightAudioUsage? = null,
)

internal const val DEFAULT_SAMPLE_RATE = 48_000
internal const val DEFAULT_FRAMES_PER_BUFFER = 256
