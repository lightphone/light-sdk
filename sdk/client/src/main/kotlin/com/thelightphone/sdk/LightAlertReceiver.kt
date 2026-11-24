package com.thelightphone.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires scheduled alerts and re-arms them after a reboot or update. The Light Gradle plugin
 * declares it for tools with `capabilities = ["alerts"]` in `lighttool.toml`; tools receive the
 * alert through [LightEntryPoint.onAlert].
 */
internal class LightAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> LightAlertScheduler.restore(context)
            LightAlertScheduler.ACTION_FIRE -> {
                val id = intent.getStringExtra(LightAlertScheduler.EXTRA_ALERT_ID) ?: return
                LightAlertScheduler.fire(context, id)
            }
        }
    }
}

/** An alert that has arrived. Call [dismiss] once the user has dealt with it. */
class LightAlert internal constructor(
    val id: String,
    val title: String,
    val text: String,
    private val context: Context,
) {
    /**
     * Ends the alert: clears its notification, so LightOS stops bringing the tool back each time
     * the screen turns off. Until then the tool is what the user sees whenever the screen turns on.
     */
    fun dismiss() {
        LightAlertNotification.dismiss(context, id)
    }

    override fun toString() = "LightAlert(id=$id, title=$title, text=$text)"
}
