package com.thelightphone.sdk.audio

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays a queue of local, bundled, or remote audio with observable playback
 * state.
 *
 * Transient focus loss pauses and later resumes playback; duckable loss lowers
 * volume. Call [release] when the owning screen is destroyed.
 */
class LightAudioPlayer internal constructor(
    context: Context,
    usage: LightAudioUsage = LightAudioUsage.Music
) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _isPlaying = MutableStateFlow(false)
    private val _currentMediaItemIndex = MutableStateFlow(NO_MEDIA_ITEM)
    private var positionJob: Job? = null
    private var pausedForTransientLoss = false
    private var released = false

    /** Current position in milliseconds, updated while playing. */
    val positionMs: StateFlow<Long> = _positionMs
    /** Resolved duration in milliseconds, or `0` while unknown/unavailable. */
    val durationMs: StateFlow<Long> = _durationMs
    /** Whether the platform is actively advancing playback. */
    val isPlaying: StateFlow<Boolean> = _isPlaying
    /** Current queue index, or `-1` when the queue is empty. */
    val currentMediaItemIndex: StateFlow<Int> = _currentMediaItemIndex

    private val player = ExoPlayer.Builder(context).build().apply player@{
        setAudioAttributes(usage.toMedia3AudioAttributes(), false)
        addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // `this@player` is the ExoPlayer (Int index), not the wrapper's StateFlow.
                _currentMediaItemIndex.value = if (mediaItem == null) {
                    NO_MEDIA_ITEM
                } else {
                    this@player.currentMediaItemIndex
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                    updatePosition()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateDuration()
                updatePosition()
                if (playbackState == Player.STATE_ENDED) {
                    stopPositionUpdates()
                    abandonFocus()
                }
            }
        })
    }

    private val focus = AudioFocusHelper(
        context = context,
        usage = usage,
        gainType = AudioManager.AUDIOFOCUS_GAIN,
        onFocusChange = ::onAudioFocusChange
    )

    /** Playback rate, clamped to a minimum positive rate. */
    var speed: Float = 1.0f
        set(value) {
            field = value.coerceAtLeast(MIN_SPEED)
            player.playbackParameters = PlaybackParameters(field)
        }

    /** Enables the platform player's silence-skipping behavior. */
    var skipSilence: Boolean = false
        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        set(value) {
            field = value
            player.skipSilenceEnabled = value
        }

    /** When `true`, playback pauses at the end of each queue item instead of advancing. */
    var pauseAtEndOfMediaItems: Boolean = false
        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        set(value) {
            field = value
            player.pauseAtEndOfMediaItems = value
        }

    /** Replaces the queue with [file] and prepares it for playback. */
    fun setSource(file: File) {
        setQueue(listOf(file), metadata = null)
    }

    internal fun setQueue(files: List<File>, metadata: LightMediaMetadata?) {
        setMediaQueue(files.map { file ->
            LightAudioItem(
                source = LightAudioSource.FileSource(file),
                metadata = metadata ?: LightMediaMetadata(file.nameWithoutExtension),
            )
        })
    }

    /**
     * Replaces and prepares the queue, selecting [startIndex]. An empty list
     * clears playback and ignores [startIndex].
     *
     * @throws IllegalArgumentException when a non-empty queue has an invalid
     *   [startIndex]
     */
    fun setMediaQueue(items: List<LightAudioItem>, startIndex: Int = 0) {
        if (items.isEmpty()) {
            player.clearMediaItems()
            _currentMediaItemIndex.value = NO_MEDIA_ITEM
            updateDuration()
            updatePosition()
            return
        }
        require(startIndex in items.indices) { "Start index must reference a queue item" }
        val mediaItems = items.mapIndexed { index, item -> item.toMediaItem(index) }
        player.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
        _currentMediaItemIndex.value = startIndex
        player.prepare()
        updateDuration()
        updatePosition()
    }

    /**
     * Start or resume playback if audio focus is available.
     *
     * Observe [isPlaying] for the actual playback state.
     */
    fun play() {
        if (released || !focus.request()) {
            return
        }
        player.play()
    }

    /** Pauses playback and abandons audio focus. */
    fun pause() {
        pausedForTransientLoss = false
        player.pause()
        abandonFocus()
    }

    /** Stops playback, returns to position zero, and abandons audio focus. */
    fun stop() {
        pausedForTransientLoss = false
        player.stop()
        player.seekTo(0L)
        updatePosition()
        abandonFocus()
    }

    /** Seeks to [ms], clamped to the resolved duration. Unknown duration clamps to zero. */
    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceIn(0L, player.duration.validDuration()))
        updatePosition()
    }

    /** Seeks backward 15 seconds, clamped to the item bounds. */
    fun skipBack() {
        seekTo(skipPosition(positionMs.value, durationMs.value, -SKIP_INTERVAL_MS))
    }

    /** Seeks forward 15 seconds, clamped to the item bounds. */
    fun skipForward() {
        seekTo(skipPosition(positionMs.value, durationMs.value, SKIP_INTERVAL_MS))
    }

    /** Selects the next queue item when one exists. */
    fun skipToNext() {
        player.seekToNextMediaItem()
    }

    /** Selects the previous queue item when one exists. */
    fun skipToPrevious() {
        player.seekToPreviousMediaItem()
    }

    /** Permanently releases playback, focus, and state-update resources. Idempotent. */
    fun release() {
        if (released) return
        released = true
        stopPositionUpdates()
        abandonFocus()
        player.release()
        scope.cancel()
    }

    private fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedForTransientLoss = false
                scope.launch { player.pause() }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedForTransientLoss = player.isPlaying
                scope.launch { player.pause() }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player.volume = DUCKED_VOLUME
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                player.volume = FULL_VOLUME
                if (pausedForTransientLoss) {
                    pausedForTransientLoss = false
                    scope.launch { play() }
                }
            }
        }
    }

    private fun abandonFocus() {
        focus.abandon()
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                updatePosition()
                updateDuration()
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun updatePosition() {
        _positionMs.value = player.currentPosition.coerceAtLeast(0L)
    }

    private fun updateDuration() {
        _durationMs.value = player.duration.validDuration()
    }
}

internal fun LightAudioItem.toMediaItem(queueIndex: Int): MediaItem {
    val uri = Uri.parse(source.uriString())
    return MediaItem.Builder()
        .setUri(uri)
        .setMediaId(uri.toString())
        .setMediaMetadata(metadata.toMedia3Metadata(queueIndex))
        .build()
}

internal fun LightAudioSource.uriString(): String = when (this) {
    is LightAudioSource.FileSource -> Uri.fromFile(file).toString()
    is LightAudioSource.AssetSource -> "asset:///${assetPath.trimStart('/')}"
    is LightAudioSource.UrlSource -> url
}

private fun LightMediaMetadata.toMedia3Metadata(queueIndex: Int): MediaMetadata {
    return MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setDurationMs(durationMs)
        .setTrackNumber(queueIndex + 1)
        .build()
}

internal fun skipPosition(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
    return (positionMs + deltaMs).coerceIn(0L, durationMs.validDuration())
}

private fun Long.validDuration(): Long = takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L

private const val SKIP_INTERVAL_MS = 15_000L
private const val POSITION_POLL_MS = 250L
private const val MIN_SPEED = 0.1f
private const val DUCKED_VOLUME = 0.2f
private const val FULL_VOLUME = 1.0f
private const val NO_MEDIA_ITEM = -1
