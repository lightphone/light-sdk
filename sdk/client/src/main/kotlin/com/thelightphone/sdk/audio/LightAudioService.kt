package com.thelightphone.sdk.audio

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Hosts detached playback: one [ExoPlayer] and one [MediaSession], shared by
 * every controller that connects.
 *
 * Declared without `android:process`, so it runs in the tool's own process and
 * shares [detachedSessionState] with the tool that created the handle. That is
 * load-bearing, not incidental — see [DetachedSessionState].
 *
 * Started by the system when a tool builds a `MediaController` against it, and
 * stopped by itself once playback is paused and no tool holds a handle — see
 * [shouldStartIdleStop].
 */
internal class LightAudioService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleStop = Runnable { stopSelf() }

    override fun onCreate() {
        super.onCreate()

        assertSharedProcess()

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(LightAudioUsage.Music.toMedia3AudioAttributes(), true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    refreshIdleStop()
                }
            })
        }
        session = MediaSession.Builder(this, player)
            .setId(packageName)
            .setCallback(SessionCallback())
            .build()
        detachedSessionState.setHandleChangedListener {
            idleHandler.post(::refreshIdleStop)
        }
        refreshIdleStop()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        idleHandler.removeCallbacks(idleStop)
        detachedSessionState.setHandleChangedListener(null)
        detachedSessionState.clearSession()
        session.release()
        player.release()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Notification, Bluetooth, and system media controllers also connect
            // here, but only our LightAudioPlayer controller requests a usage.
            if (controller.connectionHints.isToolController()) {
                val requestedUsage = controller.connectionHints.requestedUsage()
                // newPlayer checks this synchronously. Repeat it here because the
                // service state may change before the async controller connects.
                if (!isDetachedUsageCompatible(detachedSessionState.activeUsage(), requestedUsage)) {
                    return MediaSession.ConnectionResult.reject()
                }
                // An empty player has no live playback to preserve, so this fresh
                // session may take the connecting tool's requested usage.
                if (player.mediaItemCount == 0) {
                    adoptUsage(requestedUsage)
                }
            }
            return super.onConnect(session, controller)
        }

        // Connections are only a prompt to re-evaluate; the answer comes from
        // the handle, not from who is attached.
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            refreshIdleStop()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            refreshIdleStop()
        }
    }

    /** A fresh session takes the connecting handle's usage; a live one keeps its own. */
    private fun adoptUsage(usage: LightAudioUsage) {
        player.setAudioAttributes(usage.toMedia3AudioAttributes(), true)
        detachedSessionState.adoptUsage(usage)
    }

    private fun refreshIdleStop() {
        idleHandler.removeCallbacks(idleStop)
        if (shouldStartIdleStop(player.isPlaying, detachedSessionState.isHandleOpen())) {
            idleHandler.postDelayed(idleStop, IDLE_STOP_MS)
        }
    }

    /**
     * Fails loudly if someone gives this service its own process, which would
     * silently give it a second [detachedSessionState] that no tool ever opens
     * a handle on. Nothing else can catch that: it is a manifest attribute, so
     * there is no compile error, and the symptom is playback stopping a minute
     * into a pause.
     */
    private fun assertSharedProcess() {
        val process = Application.getProcessName()
        // Compared against the application's own process rather than the package
        // name: a tool may legitimately rename its default process, and that is
        // not a split.
        val appProcess = applicationInfo.processName
        check(process == appProcess) {
            "LightAudioService must run in the tool's default process ('$appProcess') " +
                "but is in '$process'. Remove android:process from its manifest entry — " +
                "detached playback state is shared with the tool as process-local state."
        }
    }
}

/**
 * `MediaSessionService` manages *foreground* state, not *service* state: a
 * paused session without a holder would keep an [ExoPlayer] and the tool's
 * process alive indefinitely.
 *
 * A handle exists before its controller connects and after it disconnects,
 * so counting controllers would let the service stop when a recconnect arrives,
 * and it would also count media3's notification controller, the platform's
 * media-button controllers, and the LightOS now-playing surface,
 * none of which own the session.
 */
internal fun shouldStartIdleStop(isPlaying: Boolean, handleOpen: Boolean): Boolean =
    !isPlaying && !handleOpen

internal const val IDLE_STOP_MS = 60_000L
