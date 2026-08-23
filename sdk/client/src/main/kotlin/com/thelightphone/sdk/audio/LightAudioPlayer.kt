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
import androidx.media3.common.Timeline
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
    private val _mediaItemCount = MutableStateFlow(0)
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
    /** Number of items in the queue, including ones added by another controller. */
    val mediaItemCount: StateFlow<Int> = _mediaItemCount.asStateFlow()
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

            // Editing the queue can move the current item without transitioning to
            // it, and in detached mode another controller may edit it at any time.
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                refreshQueueState(connectedPlayer)
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
        _mediaItemCount.value = state.mediaItemCount
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
        val mediaItems = items.map(LightAudioItem::toMediaItem)
        commands.dispatch { player ->
            player.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
            _currentMediaItemIndex.value = startIndex
            player.prepare()
            updateDuration(player)
            updatePosition(player)
        }
    }

    /**
     * Appends [item] to the queue, preparing playback when the queue was idle.
     */
    fun addMediaItem(item: LightAudioItem) {
        val mediaItem = item.toMediaItem()
        commands.dispatch { player ->
            player.addMediaItem(mediaItem)
            prepareIfIdle(player)
        }
    }

    /**
     * Inserts [item] at [index], shifting later items back. An [index] past the
     * end appends.
     *
     * @throws IllegalArgumentException when [index] is negative
     */
    fun addMediaItem(index: Int, item: LightAudioItem) {
        require(index >= 0) { "Queue index must not be negative" }
        val mediaItem = item.toMediaItem()
        commands.dispatch { player ->
            player.addMediaItem(index.coerceAtMost(player.mediaItemCount), mediaItem)
            prepareIfIdle(player)
        }
    }

    /**
     * Removes the item at [index]. Removing the current item advances to the
     * next one. An [index] past the end changes nothing.
     *
     * @throws IllegalArgumentException when [index] is negative
     */
    fun removeMediaItem(index: Int) {
        require(index >= 0) { "Queue index must not be negative" }
        commands.dispatch { player ->
            if (index < player.mediaItemCount) player.removeMediaItem(index)
        }
    }

    /**
     * Moves the item at [fromIndex] to [toIndex] without interrupting playback
     * of the current item. A [toIndex] past the end moves to the end, and a
     * [fromIndex] past the end changes nothing.
     *
     * @throws IllegalArgumentException when either index is negative
     */
    fun moveMediaItem(fromIndex: Int, toIndex: Int) {
        require(fromIndex >= 0 && toIndex >= 0) { "Queue indices must not be negative" }
        commands.dispatch { player ->
            val lastIndex = player.mediaItemCount - 1
            if (fromIndex <= lastIndex) {
                player.moveMediaItem(fromIndex, toIndex.coerceAtMost(lastIndex))
            }
        }
    }

    /**
     * Replaces the item at [index] with [item]. An [index] past the end changes
     * nothing.
     *
     * Replacing the current item keeps playing it until the new source is ready,
     * which is what makes this the way to swap in a re-resolved URL for audio
     * that is already playing. Give both items the same [LightAudioItem.id] so
     * the swap reads as one continuing track rather than two.
     *
     * @throws IllegalArgumentException when [index] is negative
     */
    fun replaceMediaItem(index: Int, item: LightAudioItem) {
        require(index >= 0) { "Queue index must not be negative" }
        val mediaItem = item.toMediaItem()
        commands.dispatch { player ->
            if (index < player.mediaItemCount) player.replaceMediaItem(index, mediaItem)
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

    private fun refreshQueueState(player: Player) {
        val state = player.snapshotState()
        _mediaItemCount.value = state.mediaItemCount
        _currentMediaItemIndex.value = state.currentMediaItemIndex
        _durationMs.value = state.durationMs
    }

    /**
     * A player that has never been prepared, or was stopped, ignores its queue
     * until asked again. Adding to such a queue reads as "play this", so ask.
     */
    private fun prepareIfIdle(player: Player) {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }
}

internal suspend fun awaitPlayerReady(
    availability: StateFlow<LightAudioPlayerAvailability>,
): Boolean = availability.first { it != LightAudioPlayerAvailability.Initializing } ==
    LightAudioPlayerAvailability.Ready

internal fun LightAudioItem.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(Uri.parse(source.uriString()))
    .setMediaId(stableId())
    .setMediaMetadata(metadata.toMedia3Metadata())
    .build()

/** This item's [LightAudioItem.id], or its location when identity is location. */
internal fun LightAudioItem.stableId(): String = id ?: source.uriString()

internal fun LightAudioSource.uriString(): String = when (this) {
    is LightAudioSource.FileSource -> Uri.fromFile(file).toString()
    is LightAudioSource.AssetSource -> "asset:///${assetPath.trimStart('/')}"
    is LightAudioSource.UrlSource -> url
}

private fun LightMediaMetadata.toMedia3Metadata(): MediaMetadata {
    return MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setDurationMs(durationMs)
        .build()
}

internal fun skipPosition(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
    return (positionMs + deltaMs).coerceIn(0L, durationMs.validDuration())
}

internal data class ConnectedPlayerState(
    val currentMediaItemIndex: Int,
    val mediaItemCount: Int,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
)

internal fun Player.snapshotState(): ConnectedPlayerState = ConnectedPlayerState(
    currentMediaItemIndex = if (mediaItemCount == 0) NO_MEDIA_ITEM else currentMediaItemIndex,
    mediaItemCount = mediaItemCount,
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
