package com.thelightphone.sdk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

internal object LightAlertScheduler {
    private const val TAG = "LightAlertScheduler"
    const val ACTION_FIRE = "com.thelightphone.sdk.action.FIRE_ALERT"

    fun schedule(
        context: Context,
        id: String,
        triggerAtMillis: Long,
        title: String,
        text: String,
    ) {
        val appContext = context.applicationContext
        val due = triggerAtMillis <= System.currentTimeMillis()
        if (!due) requireExactAlarms(appContext)

        LightAlertStore.update(appContext) { current ->
            current.filterNot { it.id == id } + LightAlertRecord(
                id = id,
                title = title,
                text = text,
                triggerAtMillis = triggerAtMillis,
            )
        }
        disarm(appContext, id)
        if (due) {
            fire(appContext, id)
        } else {
            appContext.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                fireIntent(appContext, id),
            )
        }
    }

    fun cancel(context: Context, id: String) {
        val appContext = context.applicationContext
        LightAlertStore.update(appContext) { current -> current.filterNot { it.id == id } }
        disarm(appContext, id)
    }

    fun fire(context: Context, id: String) {
        val appContext = context.applicationContext
        val record = LightAlertStore.read(appContext).find { it.id == id } ?: return
        deliver(appContext, record)
    }

    fun restore(context: Context) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val canArm = alarmManager.canScheduleExactAlarms()
        LightAlertStore.read(appContext).forEach { record ->
            if (record.triggerAtMillis <= now) {
                deliver(appContext, record)
            } else if (canArm) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    record.triggerAtMillis,
                    fireIntent(appContext, record.id),
                )
            }
        }
    }

    private fun disarm(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(fireIntent(context, id))
    }

    private fun deliver(context: Context, record: LightAlertRecord) {
        val probe = Intent(LightAlertReceiver.ACTION).setPackage(context.packageName)
        val matches = context.packageManager.queryBroadcastReceivers(
            probe,
            PackageManager.MATCH_ALL,
        )
        matches.forEach { info ->
            val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            val intent = Intent(LightAlertReceiver.ACTION).setComponent(component).apply {
                putExtra(LightAlertReceiver.EXTRA_ALERT_ID, record.id)
                putExtra(LightAlertReceiver.EXTRA_TITLE, record.title)
                putExtra(LightAlertReceiver.EXTRA_TEXT, record.text)
            }
            try {
                val receiver = context.classLoader
                    .loadClass(info.activityInfo.name)
                    .getDeclaredConstructor()
                    .newInstance() as BroadcastReceiver
                receiver.onReceive(context, intent)
            } catch (error: Exception) {
                Log.e(TAG, "Alert receiver failed: ${component.className}", error)
            }
        }
    }

    private fun fireIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, LightAlertAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            data = alertUri(id)
            putExtra(LightAlertReceiver.EXTRA_ALERT_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alertUri(id: String): Uri = Uri.parse("light-alert:${Uri.encode(id)}")

    private fun requireExactAlarms(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms()) {
            throw IllegalStateException(
                "Scheduling an alert requires android.permission.SCHEDULE_EXACT_ALARM",
            )
        }
    }
}
