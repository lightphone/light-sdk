package com.thelightphone.sdk

import android.Manifest
import android.R
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import com.thelightphone.sdk.shared.LightConstants
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

class LightNotifications(private val context: Context) {
    fun canScheduleExactAlerts(): Boolean =
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun canPostNotifications(): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

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
     * Brings the tool forward with [title] and [text] when [triggerAtMillis] arrives, then calls
     * [LightEntryPoint.onAlert]. The same [id] replaces an alert that was already scheduled.
     * A time that has already passed is delivered immediately. The alert stays scheduled until
     * [cancel]. Requires `capabilities = ["alerts"]` in `lighttool.toml`.
     */
    fun schedule(id: String, triggerAtMillis: Long, title: String, text: String) {
        require(id.isNotBlank()) { "alert id must not be blank" }
        LightAlertScheduler.schedule(context, id, triggerAtMillis, title, text)
    }

    /**
     * Ids of the alerts that are scheduled and not yet dropped. An alert leaves the set when
     * [cancel] drops it, or when it is delivered and [LightEntryPoint.onAlert] returns true.
     */
    val scheduledAlertIds: StateFlow<Set<String>>
        get() = LightAlertStore.ids(context)

    /**
     * Drops an alert so it does not fire, and so a reboot does not deliver it again. If the alert
     * is already showing, this also dismisses it, like [LightAlert.dismiss].
     */
    fun cancel(id: String) {
        LightAlertScheduler.cancel(context, id)
        LightAlertNotification.dismiss(context, id)
    }
}

/**
 * Posts the notification for an alert that has arrived. LightOS sees it and opens [LightActivity]
 * with [EXTRA_BRING_FORWARD] and turns the screen on. The screen then times out as usual, and
 * LightOS brings the tool back whenever the screen turns off, until [LightAlert.dismiss] clears
 * the notification.
 */
internal object LightAlertNotification {
    // Without a runtime grant the post fails, which is logged.
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun post(context: Context, record: LightAlertRecord) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
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
        val launch = Intent(appContext, LightActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_BRING_FORWARD, true)
            putExtra(LightConstants.EXTRA_ALERT_ID, record.id)
        }
        val pending = PendingIntent.getActivity(
            appContext,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val shownTitle = record.title.ifBlank {
            appContext.applicationInfo.loadLabel(appContext.packageManager).toString()
        }
        val notification = Notification.Builder(appContext, LightAlertChannel.ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(shownTitle)
            .setContentText(record.text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .addExtras(Bundle().apply { putString(LightConstants.EXTRA_ALERT_ID, record.id) })
            .build()
        // Tagged with the alert id, so dismissing one alert leaves any other alone.
        runCatching { manager.notify(record.id, ALERT_NOTIFICATION_ID, notification) }
            .onFailure { Log.e(TAG, "Could not post the alert notification", it) }
    }

    /** Clears the alert's notification if it is showing, and lets the tool's screen go. */
    fun dismiss(context: Context, id: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val showing = manager.activeNotifications.any { it.tag == id && it.id == ALERT_NOTIFICATION_ID }
        if (!showing) return
        manager.cancel(id, ALERT_NOTIFICATION_ID)
        LightAlertPresentation.release()
    }
}

internal const val EXTRA_BRING_FORWARD = LightConstants.EXTRA_BRING_FORWARD

internal object LightAlertPresentation {
    private var activity: WeakReference<android.app.Activity>? = null

    // LightOS wakes the screen for the alert itself. The activity must not turn the screen on,
    // or LightOS bringing it back as the screen turns off would wake the screen straight away.
    fun hold(activity: android.app.Activity) {
        this.activity = WeakReference(activity)
        activity.setShowWhenLocked(true)
    }

    fun release() {
        val held = activity?.get() ?: return
        activity = null
        // Undo hold(), or every later resume would show over the lock screen.
        held.setShowWhenLocked(false)
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

private const val ALERT_NOTIFICATION_ID = 2
private const val TAG = "LightNotifications"
