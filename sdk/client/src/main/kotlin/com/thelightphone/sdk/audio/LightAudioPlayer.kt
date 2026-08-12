package com.thelightphone.sdk.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Whether a [LightAudioPlayer] is initializing, accepts commands, or is terminal. */
enum class LightAudioPlayerAvailability {
    Initializing,
    Ready,
    Released,
}

/**
 * Plays a queue of local, bundled, or remote audio with observable playback
 * state.
 *
 * Transient focus loss pauses and later resumes playback; duckable loss lowers
 * volume. Call [release] when the owning screen is destroyed.
 */
class LightAudioPlayer internal constructor(
    context: Context,
    usage: LightAudioUsage = LightAudioUsage.Music,
    internal val playback: LightAudioPlayback = LightAudioPlayback.Attached,
    private val onRelease: () -> Unit = {},
) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _isPlaying = MutableStateFlow(false)
    private val _currentMediaItemIndex = MutableStateFlow(NO_MEDIA_ITEM)
    private val _error = MutableStateFlow<LightAudioError?>(null)
    private val commands = PendingPlayerCommands()
    private var positionJob: Job? = null
    private var player: Player? = null
    private var cancelPendingConnection: (() -> Unit)? = null
    private var released = false

    /** Current position in milliseconds, updated while playing. */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    /** Resolved duration in milliseconds, or `0` while unknown/unavailable. */
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    /** Whether the platform is actively advancing playback. */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    /** Current queue index, or `-1` when the queue is empty. */
    val currentMediaItemIndex: StateFlow<Int> = _currentMediaItemIndex.asStateFlow()
    /** Current playback failure, or `null` after successful re-preparation. */
    val error: StateFlow<LightAudioError?> = _error.asStateFlow()
    /** Connection and command-acceptance lifecycle of this player. */
    val availability: StateFlow<LightAudioPlayerAvailability> = commands.availability

    init {
        when (playback) {
            LightAudioPlayback.Attached -> connectPlayer(
                ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(usage.toMedia3AudioAttributes(), true)
                },
            )
            LightAudioPlayback.Detached -> connectDetachedPlayer(context, usage)
        }
    }

    private fun connectPlayer(connectedPlayer: Player) {
        if (released) {
            connectedPlayer.release()
            return
        }
        player = connectedPlayer
        connectedPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentMediaItemIndex.value = if (mediaItem == null) {
                    NO_MEDIA_ITEM
                } else {
                    connectedPlayer.currentMediaItemIndex
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                    updatePosition(connectedPlayer)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateDuration(connectedPlayer)
                updatePosition(connectedPlayer)
                if (playbackState == Player.STATE_ENDED) {
                    stopPositionUpdates()
                }
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                _error.value = error?.toLightAudioError(connectedPlayer.currentMediaItemIndex)
            }
        })
        val state = connectedPlayer.snapshotState()
        _currentMediaItemIndex.value = state.currentMediaItemIndex
        _positionMs.value = state.positionMs
        _durationMs.value = state.durationMs
        _isPlaying.value = state.isPlaying
        _error.value = connectedPlayer.playerError
            ?.toLightAudioError(connectedPlayer.currentMediaItemIndex)
        if (state.isPlaying) {
            startPositionUpdates()
        } else {
            stopPositionUpdates()
        }
        commands.ready(connectedPlayer)
    }

    private fun connectDetachedPlayer(context: Context, usage: LightAudioUsage) {
        val token = SessionToken(context, ComponentName(context, LightAudioService::class.java))
        val future = MediaController.Builder(context, token)
            .setConnectionHints(detachedConnectionHints(usage))
            .buildAsync()
        cancelPendingConnection = { future.cancel(false) }
        future.addListener(
            {
                cancelPendingConnection = null
                runCatching(future::get)
                    .onSuccess(::connectPlayer)
                    .onFailure { release() }
            },
            context.mainExecutor,
        )
    }

    /** Playback rate, clamped to a minimum positive rate. */
    var speed: Float = 1.0f
        set(value) {
            val speed = value.coerceAtLeast(MIN_SPEED)
            commands.dispatch { it.playbackParameters = PlaybackParameters(speed) }
            field = speed
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
            commands.dispatch { player ->
                player.clearMediaItems()
                _currentMediaItemIndex.value = NO_MEDIA_ITEM
                updateDuration(player)
                updatePosition(player)
            }
            return
        }
        require(startIndex in items.indices) { "Start index must reference a queue item" }
        val mediaItems = items.mapIndexed { index, item -> item.toMediaItem(index) }
        commands.dispatch { player ->
            player.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
            _currentMediaItemIndex.value = startIndex
            player.prepare()
            updateDuration(player)
            updatePosition(player)
        }
    }

    /**
     * Start or resume playback if audio focus is available.
     *
     * Observe [isPlaying] for the actual playback state.
     */
    fun play() {
        commands.dispatch(Player::play)
    }

    /** Pauses playback. */
    fun pause() {
        commands.dispatch(Player::pause)
    }

    /** Stops playback and returns to position zero. */
    fun stop() {
        commands.dispatch { player ->
            player.stop()
            player.seekTo(0L)
            updatePosition(player)
        }
    }

    /** Seeks to [ms], clamped to the resolved duration. Unknown duration clamps to zero. */
    fun seekTo(ms: Long) {
        commands.dispatch { player ->
            player.seekTo(ms.coerceIn(0L, player.duration.validDuration()))
            updatePosition(player)
        }
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
        commands.dispatch(Player::seekToNextMediaItem)
    }

    /** Selects the previous queue item when one exists. */
    fun skipToPrevious() {
        commands.dispatch(Player::seekToPreviousMediaItem)
    }

    /** Waits for connection, returning `false` if this player is released first. */
    suspend fun awaitReady(): Boolean = awaitPlayerReady(availability)

    /**
     * Releases this handle. Attached playback stops; detached playback continues
     * until [stop] is called or the service's idle rule fires. Idempotent.
     */
    fun release() {
        if (released) return
        released = true
        stopPositionUpdates()
        commands.release()
        try {
            cancelPendingConnection?.invoke()
            cancelPendingConnection = null
            player?.release()
            player = null
            scope.cancel()
        } finally {
            onRelease()
        }
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                player?.let {
                    updatePosition(it)
                    updateDuration(it)
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun updatePosition(player: Player) {
        _positionMs.value = player.currentPosition.coerceAtLeast(0L)
    }

    private fun updateDuration(player: Player) {
        _durationMs.value = player.duration.validDuration()
    }
}

internal suspend fun awaitPlayerReady(
    availability: StateFlow<LightAudioPlayerAvailability>,
): Boolean = availability.first { it != LightAudioPlayerAvailability.Initializing } ==
    LightAudioPlayerAvailability.Ready

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

internal data class ConnectedPlayerState(
    val currentMediaItemIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
)

internal fun Player.snapshotState(): ConnectedPlayerState = ConnectedPlayerState(
    currentMediaItemIndex = if (mediaItemCount == 0) NO_MEDIA_ITEM else currentMediaItemIndex,
    positionMs = currentPosition.coerceAtLeast(0L),
    durationMs = duration.validDuration(),
    isPlaying = isPlaying,
)

private fun Long.validDuration(): Long = takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L

private const val SKIP_INTERVAL_MS = 15_000L
private const val POSITION_POLL_MS = 250L
private const val MIN_SPEED = 0.1f
/** Queue index reported when a player has no media item. */
const val NO_MEDIA_ITEM = -1
