package com.thelightphone.sdk.emulator

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the lock screen shows for the currently active media session. */
data class LightNowPlayingState(
    val title: String,
    val artist: String?,
    val isPlaying: Boolean,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
)

/**
 * Reads the device's active [android.media.session.MediaSession]s and exposes
 * the most relevant one as [state], plus transport controls that route back to
 * it. This is the LightOS side of the media-session bridge: any tool that
 * publishes a session (e.g. via `LightMediaSession`) shows up here.
 *
 * Requires `android.permission.MEDIA_CONTENT_CONTROL`, which the emulator holds
 * as a platform-signed system app — so it may pass a `null` notification
 * listener to [MediaSessionManager.getActiveSessions]. Everything runs on the
 * main thread.
 */
class NowPlayingReader(context: Context) {

    private val manager: MediaSessionManager =
        requireNotNull(context.applicationContext.getSystemService(MediaSessionManager::class.java))
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<LightNowPlayingState?>(null)
    val state: StateFlow<LightNowPlayingState?> = _state.asStateFlow()

    private var controller: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // While the selected session keeps playing, just refresh. Once it
            // stops/pauses, another tool may now be the "current" one, so
            // re-evaluate selection (which prefers a playing session).
            if (state?.state == PlaybackState.STATE_PLAYING) publish()
            else selectController(activeSessions())
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = selectController(activeSessions())
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            selectController(controllers ?: emptyList())
        }

    fun start() {
        manager.addOnActiveSessionsChangedListener(sessionsListener, null, handler)
        selectController(activeSessions())
    }

    fun stop() {
        manager.removeOnActiveSessionsChangedListener(sessionsListener)
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    fun playPause() {
        val tc = controller?.transportControls ?: return
        if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
    }

    fun next() = controller?.transportControls?.skipToNext() ?: Unit

    fun previous() = controller?.transportControls?.skipToPrevious() ?: Unit

    private fun activeSessions(): List<MediaController> =
        runCatching { manager.getActiveSessions(null) }.getOrDefault(emptyList())

    /** Prefer a playing session; otherwise the first available one. */
    private fun selectController(controllers: List<MediaController>) {
        val chosen = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
        if (chosen?.sessionToken == controller?.sessionToken) {
            publish()
            return
        }
        controller?.unregisterCallback(controllerCallback)
        controller = chosen
        controller?.registerCallback(controllerCallback, handler)
        publish()
    }

    private fun publish() {
        val c = controller
        val metadata = c?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (c == null || title.isNullOrBlank()) {
            _state.value = null
            return
        }
        val playback = c.playbackState
        val actions = playback?.actions ?: 0L
        _state.value = LightNowPlayingState(
            title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.ifBlank { null },
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            hasNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            hasPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
        )
    }
}
