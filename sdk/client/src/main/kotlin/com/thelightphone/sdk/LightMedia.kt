package com.thelightphone.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log

/**
 * The track a tool wants shown on the lock screen / system media controls.
 *
 * [durationMs] may be `0` when unknown (e.g. a live stream); the lock screen
 * then omits the scrubber. [hasNext]/[hasPrevious] gate whether the skip
 * controls are offered — set them from the tool's own queue position.
 */
data class LightNowPlaying(
    val title: String,
    val artist: String? = null,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

/** Whether the tool is currently playing, and where the playhead is. */
data class LightPlaybackStatus(
    val isPlaying: Boolean,
    val positionMs: Long = 0,
)

/**
 * Control callbacks invoked when the user taps a transport button on the lock
 * screen, a Bluetooth/wired headset, or any other MediaSession client. Every
 * method is dispatched on the main thread. Only the actions you advertise via
 * [LightNowPlaying] (next/previous) and the always-present play/pause/stop are
 * delivered.
 */
interface LightMediaControls {
    fun onPlay() {}
    fun onPause() {}
    fun onNext() {}
    fun onPrevious() {}
    fun onSeekTo(positionMs: Long) {}
    fun onStop() {}
}

/**
 * Publishes the tool's playback to Android's media framework so it can be
 * surfaced outside the tool's own UI — on the LightOS lock screen and via
 * headset/Bluetooth transport buttons.
 *
 * A tool declares `android.permission.FOREGROUND_SERVICE`,
 * `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and
 * `android.permission.POST_NOTIFICATIONS` in its `lighttool.toml`, then:
 *
 * ```
 * LightMediaSession.attach(lightContext)   // once, e.g. from your initial screen
 * LightMediaSession.setControls(object : LightMediaControls {
 *     override fun onPlay() = MusicPlayer.togglePlayPause()
 *     override fun onNext() = MusicPlayer.next()
 *     // …
 * })
 * // whenever playback state changes:
 * LightMediaSession.update(nowPlaying, status)
 * // when playback ends / the tool tears down:
 * LightMediaSession.release()
 * ```
 *
 * Process-global and main-thread-only, mirroring a typical tool's playback
 * engine. The underlying [MediaSession] lives inside [LightMediaService] (a
 * foreground service) so audio and the lock-screen surface survive the tool
 * being navigated away from or the screen turning off.
 */
object LightMediaSession {

    private const val TAG = "LightMediaSession"
    internal const val NOTIFICATION_ID = 0x11_9E
    internal const val CHANNEL_ID = "light_media_playback"

    private var session: MediaSession? = null
    private var controls: LightMediaControls? = null

    // Captured once via [attach] (always the application context, never an
    // Activity) so context-less engines can drive the session through [update].
    private var appContext: Context? = null

    // Last pushed state, retained so the foreground service can (re)build its
    // notification at any time — including in onStartCommand, before the first
    // update() lands.
    private var lastNowPlaying: LightNowPlaying? = null
    private var lastStatus: LightPlaybackStatus? = null
    private var started = false

    // The notification is only re-posted when a user-visible field changes, so
    // a high-frequency position ticker can refresh the session (for the
    // scrubber) via [update] without thrashing the notification.
    private var lastNotifiedKey: String? = null

    /**
     * Binds the media session to the tool's application context. Call once
     * (e.g. from your initial screen) before [update]. Safe to call repeatedly.
     */
    fun attach(lightContext: SealedLightContext) {
        appContext = lightContext.androidContext.applicationContext
    }

    /** Registers (or clears) the transport-control handlers. Safe to call before [update]. */
    fun setControls(controls: LightMediaControls?) {
        this.controls = controls
    }

    /**
     * Pushes the current track and playback status to the media framework,
     * starting the foreground media session the first time it's called.
     * No-ops (with a warning) until [attach] has been called.
     */
    fun update(nowPlaying: LightNowPlaying, status: LightPlaybackStatus) {
        val appContext = appContext ?: run {
            Log.w(TAG, "update() before attach(); ignoring")
            return
        }
        lastNowPlaying = nowPlaying
        lastStatus = status

        val session = ensureSession(appContext)
        session.setMetadata(nowPlaying.toMetadata())
        session.setPlaybackState(status.toPlaybackState(nowPlaying))
        if (!session.isActive) session.isActive = true

        if (!started) {
            // Promote the process to a foreground media service so playback and
            // the lock-screen surface outlive the tool's UI.
            appContext.startForegroundService(Intent(appContext, LightMediaService::class.java))
            started = true
            lastNotifiedKey = notificationKey(nowPlaying, status)
        } else {
            // Already foreground — only re-post the notification when something
            // the user can see actually changed.
            val key = notificationKey(nowPlaying, status)
            if (key != lastNotifiedKey) {
                lastNotifiedKey = key
                notificationManager(appContext).notify(NOTIFICATION_ID, buildNotification(appContext))
            }
        }
    }

    /** Tears down the media session and stops the foreground service. */
    fun release() {
        val appContext = appContext
        started = false
        lastNowPlaying = null
        lastStatus = null
        lastNotifiedKey = null
        session?.let { s ->
            runCatching { s.isActive = false }
            runCatching { s.release() }
        }
        session = null
        appContext?.let {
            runCatching { it.stopService(Intent(it, LightMediaService::class.java)) }
        }
    }

    private fun notificationKey(nowPlaying: LightNowPlaying, status: LightPlaybackStatus): String =
        "${nowPlaying.title}|${nowPlaying.artist}|${status.isPlaying}|${nowPlaying.hasNext}|${nowPlaying.hasPrevious}"

    private fun ensureSession(context: Context): MediaSession =
        session ?: MediaSession(context, TAG).also { s ->
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { controls?.onPlay() }
                override fun onPause() { controls?.onPause() }
                override fun onSkipToNext() { controls?.onNext() }
                override fun onSkipToPrevious() { controls?.onPrevious() }
                override fun onSeekTo(pos: Long) { controls?.onSeekTo(pos) }
                override fun onStop() { controls?.onStop() }
            })
            session = s
        }

    private fun LightNowPlaying.toMetadata(): MediaMetadata =
        MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            .build()

    private fun LightPlaybackStatus.toPlaybackState(nowPlaying: LightNowPlaying): PlaybackState {
        var actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO
        if (nowPlaying.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (nowPlaying.hasPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        return PlaybackState.Builder()
            .setActions(actions)
            .setState(state, positionMs, if (isPlaying) 1f else 0f)
            .build()
    }

    /** Builds the foreground-service notification from the last pushed state. */
    internal fun buildNotification(context: Context): Notification {
        ensureChannel(context)
        val nowPlaying = lastNowPlaying
        val style = Notification.MediaStyle().apply {
            session?.sessionToken?.let { setMediaSession(it) }
        }
        return Notification.Builder(context, CHANNEL_ID)
            // Framework drawable so the SDK needn't ship a resource; the LightOS
            // lock screen reads the MediaSession, not this icon.
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(nowPlaying?.title ?: "")
            .setContentText(nowPlaying?.artist ?: "")
            .setOngoing(lastStatus?.isPlaying == true)
            .setStyle(style)
            .build()
    }

    private fun ensureChannel(context: Context) {
        val manager = notificationManager(context)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
    }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    init {
        Log.d(TAG, "LightMediaSession initialized")
    }
}
