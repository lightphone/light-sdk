package com.thelightphone.sdk

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresPermission
import java.lang.ref.WeakReference

class LightNotifications(private val context: Context) {
    fun canScheduleExactAlerts(): Boolean =
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun canPostNotifications(): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /**
     * Waking the device from a background alert needs `USE_FULL_SCREEN_INTENT`.
     * This is an app op - a runtime grant check does not report it, so we provide canUseFullScreenIntent
     */
    fun canUseFullScreenIntent(): Boolean =
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun post(title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                LightNotificationChannel.ID,
                LightNotificationChannel.NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val notification = Notification.Builder(context, LightNotificationChannel.ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        manager.notify(IMMEDIATE_NOTIFICATION_ID, notification)
    }

    fun cancel() {
        context.getSystemService(NotificationManager::class.java).cancel(IMMEDIATE_NOTIFICATION_ID)
    }

    /**
     * Delivers [title] and [text] to the tool's [LightAlertReceiver] when [triggerAtMillis] arrives.
     * The same [id] replaces an alert that was already scheduled. A time that has already passed
     * is delivered immediately. The alert stays scheduled until [cancel].
     */
    fun schedule(id: String, triggerAtMillis: Long, title: String, text: String) {
        require(id.isNotBlank()) { "alert id must not be blank" }
        LightAlertScheduler.schedule(context, id, triggerAtMillis, title, text)
    }

    /** Drops an alert so it does not fire, and so a reboot does not deliver it again. */
    fun cancel(id: String) {
        LightAlertScheduler.cancel(context, id)
    }

    /**
     * Brings the tool forward and keeps the screen on.
     * Call this from [LightAlertReceiver.onAlert] when the alert should be visible
     * even if the tool is not open. Posts a full-screen intent so the screen turns on
     * while the tool is in the background. [releaseScreen] lets the screen sleep again
     * and clears that notification.
     *
     * Declare `POST_NOTIFICATIONS` and `USE_FULL_SCREEN_INTENT` permission in `lighttool.toml`, and request both before scheduling an alert
     */
    @RequiresPermission(allOf = [Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.USE_FULL_SCREEN_INTENT])
    fun bringForward(title: String = "", text: String = "") {
        val appContext = context.applicationContext
        val launch = Intent(appContext, LightActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_BRING_FORWARD, true)
        }
        postFullScreenAlert(appContext, launch, title, text)
        appContext.startActivity(launch)
    }

    fun releaseScreen() {
        LightAlertPresentation.release()
        context.getSystemService(NotificationManager::class.java)
            .cancel(FULL_SCREEN_ALERT_NOTIFICATION_ID)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun postFullScreenAlert(context: Context, launch: Intent, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                LightAlertChannel.ID,
                LightAlertChannel.NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val pending = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val shownTitle = title.ifBlank {
            context.applicationInfo.loadLabel(context.packageManager).toString()
        }
        val notification = Notification.Builder(context, LightAlertChannel.ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(shownTitle)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(FULL_SCREEN_ALERT_NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.e(TAG, "Could not post the full-screen alert", error)
        }
    }
}

internal const val EXTRA_BRING_FORWARD = "com.thelightphone.sdk.extra.BRING_FORWARD"

internal object LightAlertPresentation {
    private var activity: WeakReference<android.app.Activity>? = null

    fun hold(activity: android.app.Activity) {
        this.activity = WeakReference(activity)
        activity.setShowWhenLocked(true)
        activity.setTurnScreenOn(true)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun release() {
        activity?.get()?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

internal object LightNotificationChannel {
    const val ID = "sdk tool"
    const val NAME = "sdk tool"
}

internal object LightAlertChannel {
    const val ID = "sdk alert"
    const val NAME = "sdk alert"
}

private const val FULL_SCREEN_ALERT_NOTIFICATION_ID = 2
private const val TAG = "LightNotifications"
