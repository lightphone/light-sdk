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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Everything runs on the main looper, so no state needs synchronization.
 */
class PlatformMediaSessionObserver(private val context: Context) {

    companion object {
        private const val TAG = "PlatformMediaSessions"
        // Session extra shared with light-sdk's LightAudioService and LightOS
        private const val USAGE_EXTRA = "com.thelightphone.audio.USAGE"
    }

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<PlatformMediaSession?>(null)
    val state: StateFlow<PlatformMediaSession?> = _state.asStateFlow()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        watchPlaybackOf(controllers.orEmpty())
        refreshSessionSelection()
    }
    private val playbackCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshSessionSelection()
    }
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onExtrasChanged(extras: Bundle?) = publish()
        override fun onSessionDestroyed() {
            setSelectedController(null)
            publish()
        }
    }
    private val watchedControllers = mutableListOf<MediaController>()
    private var selectedController: MediaController? = null
    private var observing = false

    fun subscribe() {
        if (observing) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, null, handler)
        } catch (e: SecurityException) {
            Log.w(TAG, "Media sessions denied — the emulator must run as a system app", e)
            return
        }
        observing = true
        watchPlaybackOf(manager.getActiveSessions(null))
        refreshSessionSelection()
    }

    fun play() = sendTransportCommand(PlaybackState.ACTION_PLAY) { play() }
    fun pause() = sendTransportCommand(PlaybackState.ACTION_PAUSE) { pause() }
    fun previous() = sendTransportCommand(PlaybackState.ACTION_SKIP_TO_PREVIOUS) { skipToPrevious() }
    fun next() = sendTransportCommand(PlaybackState.ACTION_SKIP_TO_NEXT) { skipToNext() }
    fun rewind() = sendTransportCommand(PlaybackState.ACTION_REWIND) { rewind() }
    fun fastForward() = sendTransportCommand(PlaybackState.ACTION_FAST_FORWARD) { fastForward() }

    /** Opens the session's own activity, else launches its app. */
    fun open() {
        val controller = selectedController ?: return
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

    private fun watchPlaybackOf(controllers: List<MediaController>) {
        watchedControllers.forEach { it.unregisterCallback(playbackCallback) }
        watchedControllers.clear()
        controllers.forEach { it.registerCallback(playbackCallback, handler) }
        watchedControllers.addAll(controllers)
    }

    private fun refreshSessionSelection() {
        if (!observing) return
        val controllers = manager.getActiveSessions(null)
        val sessions = controllers.map {
            val state = it.playbackState
            SelectableMediaSession(
                id = it.sessionId,
                playing = state?.state == PlaybackState.STATE_PLAYING,
                displayable = state.isDisplayable(),
            )
        }
        val selectedId = selectPlatformMediaSession(sessions, selectedController?.sessionId)
        setSelectedController(controllers.firstOrNull { it.sessionId == selectedId })
        publish()
    }

    private fun setSelectedController(next: MediaController?) {
        if (selectedController?.sessionToken == next?.sessionToken) return
        selectedController?.unregisterCallback(controllerCallback)
        selectedController = next
        next?.registerCallback(controllerCallback, handler)
    }

    private fun publish() {
        _state.value = selectedController?.let { controller ->
            val metadata = controller.metadata
            val playback = controller.playbackState
            PlatformMediaSession(
                packageName = controller.packageName,
                title = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString(),
                artist = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString(),
                playbackState = playback?.state ?: PlaybackState.STATE_NONE,
                actions = playback?.actions ?: 0L,
                usage = controller.extras?.getString(USAGE_EXTRA),
            )
        }
    }

    private inline fun sendTransportCommand(
        action: Long,
        command: MediaController.TransportControls.() -> Unit,
    ) {
        val controller = selectedController ?: return
        val actions = controller.playbackState?.actions ?: return
        if ((actions and action) != 0L) controller.transportControls.command()
    }

    private val MediaController.sessionId: String
        get() = "$packageName:${sessionToken.hashCode()}"

    private fun PlaybackState?.isDisplayable(): Boolean = when (this?.state) {
        null, PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> false
        else -> true
    }
}
