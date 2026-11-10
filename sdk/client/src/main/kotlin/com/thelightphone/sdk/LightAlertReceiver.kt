package com.thelightphone.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives an alert when its scheduled time arrives, including if the tool is not open
 * Name the subclass in `lighttool.toml`:
 *
 * ```
 * alertReceiver = "com.example.mytool.ExampleAlertReceiver"
 * ```
 *
 * [onAlert] runs on the main thread. Show the tool's own screen or post a notification, then return
 * [dismiss] drops the alert so a reboot does not deliver it again
 */
abstract class LightAlertReceiver : BroadcastReceiver() {
    final override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra(EXTRA_ALERT_ID) ?: return
        val alert = LightAlert(
            id = id,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
        )
        onAlert(context, alert) { LightNotifications(context).cancel(id) }
    }

    abstract fun onAlert(context: Context, alert: LightAlert, dismiss: () -> Unit)

    companion object {
        const val ACTION = "com.thelightphone.sdk.action.ALERT"
        const val EXTRA_ALERT_ID = "com.thelightphone.sdk.extra.ALERT_ID"
        const val EXTRA_TITLE = "com.thelightphone.sdk.extra.ALERT_TITLE"
        const val EXTRA_TEXT = "com.thelightphone.sdk.extra.ALERT_TEXT"
    }
}

data class LightAlert(
    val id: String,
    val title: String,
    val text: String,
)
