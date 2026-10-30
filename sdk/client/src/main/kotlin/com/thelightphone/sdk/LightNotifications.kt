package com.thelightphone.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class LightNotifications(private val context: Context) {
    fun post(title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "sdk tool"
        const val CHANNEL_NAME = "sdk tool"
        const val NOTIFICATION_ID = 1
    }
}
