package com.thelightphone.sdk

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Foreground service that keeps a tool's process alive (and its [MediaSession]
 * active) while audio plays in the background. Declared in the SDK client
 * manifest with `foregroundServiceType="mediaPlayback"`; started and stopped
 * by [LightMediaSession], which owns the session and builds the notification.
 *
 * Tools never touch this class directly — they go through [LightMediaSession].
 */
class LightMediaService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            LightMediaSession.NOTIFICATION_ID,
            LightMediaSession.buildNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        // Playback state is owned by the tool + LightMediaSession, not restored
        // from a relaunched intent, so don't auto-restart with a null intent.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
