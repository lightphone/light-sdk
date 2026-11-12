package com.thelightphone.sdk.emulator

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/**
 * The platform media session the lock screen shows.
 *
 * @property playbackState an [PlaybackState] state constant
 * @property actions the [PlaybackState] actions bitmask the session supports
 * @property usage the session's [USAGE_EXTRA], e.g. "music" or "speech"
 */
data class PlatformMediaSession(
    val packageName: String,
    val title: String?,
    val artist: String?,
    val playbackState: Int,
    val actions: Long,
    val usage: String?,
) {
    val isPlaying: Boolean get() = playbackState == PlaybackState.STATE_PLAYING

    fun supports(action: Long): Boolean = (actions and action) != 0L
}

/**
 * Observes platform media sessions and exposes one selected session, the way
 * LightOS does for its Standby controls. Android only reports active sessions
 * changes when a session becomes active or inactive, not when one starts
 * playing, so every active session's playback state is watched.
 *
 * Observation starts at construction and stops when [scope] is cancelled.
 * Platform callbacks are delivered on the main looper; the flows themselves
 * hold no mutable state, so [scope] can use any dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformMediaSessionObserver(
    private val context: Context,
    scope: CoroutineScope,
) {

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val selectedController: StateFlow<MediaController?> =
        manager.activeSessions(handler)
            .flatMapLatest { controllers -> controllers.withPlaybackStates(handler) }
            .runningFold(null as MediaController?) { previous, sessions -> select(sessions, previous) }
            .distinctUntilChangedBy { it?.sessionToken }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val state: StateFlow<PlatformMediaSession?> =
        selectedController
            .flatMapLatest { it?.sessionUpdates(handler) ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun play() = sendTransportCommand(PlaybackState.ACTION_PLAY) { play() }
    fun pause() = sendTransportCommand(PlaybackState.ACTION_PAUSE) { pause() }
    fun previous() = sendTransportCommand(PlaybackState.ACTION_SKIP_TO_PREVIOUS) { skipToPrevious() }
    fun next() = sendTransportCommand(PlaybackState.ACTION_SKIP_TO_NEXT) { skipToNext() }
    fun rewind() = sendTransportCommand(PlaybackState.ACTION_REWIND) { rewind() }
    fun fastForward() = sendTransportCommand(PlaybackState.ACTION_FAST_FORWARD) { fastForward() }

    /** Opens the session's own activity, else launches its app. */
    fun open() {
        val controller = selectedController.value ?: return
        val sessionActivity = controller.sessionActivity
        if (sessionActivity != null) {
            try {
                sessionActivity.send(context, 0, null, null, null, null, backgroundActivityStartOptions())
                return
            } catch (e: PendingIntent.CanceledException) {
                Log.w(TAG, "Session activity of ${controller.packageName} was canceled", e)
            }
        }
        context.packageManager.getLaunchIntentForPackage(controller.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let(context::startActivity)
    }

    // From API 34 a PendingIntent sender must opt in to lending its own foreground status
    private fun backgroundActivityStartOptions(): Bundle = ActivityOptions.makeBasic()
        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        .toBundle()

    private inline fun sendTransportCommand(
        action: Long,
        command: MediaController.TransportControls.() -> Unit,
    ) {
        val controller = selectedController.value ?: return
        val actions = controller.playbackState?.actions ?: return
        if ((actions and action) != 0L) controller.transportControls.command()
    }

    private fun select(
        sessions: List<Pair<MediaController, PlaybackState?>>,
        previous: MediaController?,
    ): MediaController? {
        val selectable = sessions.map { (controller, state) ->
            SelectableMediaSession(
                id = controller.sessionId,
                playing = state?.state == PlaybackState.STATE_PLAYING,
                displayable = state.isDisplayable(),
            )
        }
        val selectedId = selectPlatformMediaSession(selectable, previous?.sessionId)
        return sessions.firstOrNull { (controller, _) -> controller.sessionId == selectedId }?.first
    }
}



// Session extra shared with light-sdk's LightAudioService and LightOS
private const val USAGE_EXTRA = "com.thelightphone.audio.USAGE"

/** The active sessions, now and on every change; empty if access is denied. */
private fun MediaSessionManager.activeSessions(handler: Handler): Flow<List<MediaController>> =
    callbackFlow {
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { trySend(it.orEmpty()) }
        try {
            addOnActiveSessionsChangedListener(listener, null, handler)
        } catch (e: SecurityException) {
            Log.w(TAG, "Media sessions denied — the emulator must run as a system app", e)
            close()
            return@callbackFlow
        }
        trySend(getActiveSessions(null))
        awaitClose { removeOnActiveSessionsChangedListener(listener) }
    }.conflate()

/** Each controller paired with its latest playback state, re-emitted when any of them changes. */
private fun List<MediaController>.withPlaybackStates(
    handler: Handler,
): Flow<List<Pair<MediaController, PlaybackState?>>> =
    if (isEmpty()) {
        flowOf(emptyList())
    } else {
        combine(map { controller -> controller.playbackStates(handler).map { controller to it } }) {
            it.toList()
        }
    }

private fun MediaController.playbackStates(handler: Handler): Flow<PlaybackState?> = callbackFlow {
    val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            trySend(state)
        }
    }
    registerCallback(callback, handler)
    trySend(playbackState)
    awaitClose { unregisterCallback(callback) }
}.conflate()

/** A snapshot of this session on every change, then null once it is destroyed. */
private fun MediaController.sessionUpdates(handler: Handler): Flow<PlatformMediaSession?> = callbackFlow {
    fun snapshot() = trySend(toPlatformMediaSession())

    val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { snapshot() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { snapshot() }
        override fun onExtrasChanged(extras: Bundle?) { snapshot() }
        override fun onSessionDestroyed() {
            trySend(null)
            close()
        }
    }
    registerCallback(callback, handler)
    snapshot()
    awaitClose { unregisterCallback(callback) }
}.conflate()

private fun MediaController.toPlatformMediaSession(): PlatformMediaSession {
    val metadata = metadata
    val playback = playbackState
    return PlatformMediaSession(
        packageName = packageName,
        title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString(),
        artist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString(),
        playbackState = playback?.state ?: PlaybackState.STATE_NONE,
        actions = playback?.actions ?: 0L,
        usage = extras?.getString(USAGE_EXTRA),
    )
}

private val MediaController.sessionId: String
    get() = "$packageName:${sessionToken.hashCode()}"

private fun PlaybackState?.isDisplayable(): Boolean = when (this?.state) {
    null, PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> false
    else -> true
}