package com.thelightphone.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class LightAlertAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> LightAlertScheduler.restore(context)
            LightAlertScheduler.ACTION_FIRE -> {
                val id = intent.getStringExtra(LightAlertReceiver.EXTRA_ALERT_ID) ?: return
                LightAlertScheduler.fire(context, id)
            }
        }
    }
}
