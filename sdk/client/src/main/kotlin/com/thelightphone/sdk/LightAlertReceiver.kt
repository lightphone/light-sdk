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

data class LightAlert(
    val id: String,
    val title: String,
    val text: String,
)
