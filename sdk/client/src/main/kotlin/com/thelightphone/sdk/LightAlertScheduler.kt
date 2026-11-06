package com.thelightphone.sdk

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.thelightphone.sdk.shared.LightConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object LightAlertScheduler {
    private const val TAG = "LightAlertScheduler"
    const val ACTION_FIRE = "com.thelightphone.sdk.action.FIRE_ALERT"
    const val EXTRA_ALERT_ID = LightConstants.EXTRA_ALERT_ID

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    fun schedule(
        context: Context,
        id: String,
        triggerAtMillis: Long,
        title: String,
        text: String,
    ) {
        val appContext = context.applicationContext
        requireAlertsCapability(appContext)
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

    // The alerts capability, which schedule requires, generates POST_NOTIFICATIONS.
    @SuppressLint("MissingPermission")
    private fun deliver(context: Context, record: LightAlertRecord) {
        LightAlertNotification.post(context, record)
        val alert = LightAlert(id = record.id, title = record.title, text = record.text)
        scope.launch {
            val drop = try {
                LightSdkRegistry.entryPoint?.onAlert(alert) ?: true
            } catch (error: Exception) {
                // Dropped so a failing handler does not run again on every boot.
                Log.e(TAG, "onAlert failed for ${record.id}", error)
                true
            }
            if (drop) dropDelivered(context, record)
        }
    }

    // Leaves the alert alone if onAlert scheduled the same id again.
    private fun dropDelivered(context: Context, record: LightAlertRecord) {
        var removed = false
        LightAlertStore.update(context) { current ->
            current.filterNot { it == record }.also { removed = it.size != current.size }
        }
        if (removed) disarm(context, record.id)
    }

    private fun fireIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, LightAlertReceiver::class.java).apply {
            action = ACTION_FIRE
            data = alertUri(id)
            putExtra(EXTRA_ALERT_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alertUri(id: String): Uri = Uri.parse("light-alert:${Uri.encode(id)}")

    @Suppress("DEPRECATION")
    private fun requireAlertsCapability(context: Context) {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        if (appInfo.metaData?.getBoolean(LightConstants.CAPABILITY_ALERTS_MARKER) != true) {
            throw IllegalStateException(
                """
                Scheduling an alert requires this lighttool.toml capability:
                capabilities = ["alerts"]
                """.trimIndent(),
            )
        }
    }

    private fun requireExactAlarms(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms()) {
            throw IllegalStateException(
                "Scheduling an alert requires android.permission.USE_EXACT_ALARM",
            )
        }
    }
}
