package com.thelightphone.sdk.server

import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.thelightphone.sdk.shared.LightConstants

/**
 * Has two jobs:
 * 1. monitor all notifications so we can update the toolbox UI to show which tools have active ones
 * 2. look specifically for LIGHT_ALERT flavored notifs, which mean we should foreground that tool when posted
 *
 * The host app enables this with [LightSdkServer.enableNotificationListener].
 */
class LightNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        publishActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        foregroundIfAlert(sbn)
        publishActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        publishActiveNotifications()
    }

    private fun publishActiveNotifications() {
        // Throws if called while the listener is not connected.
        val active = try {
            activeNotifications?.filterNotNull().orEmpty()
        } catch (error: SecurityException) {
            Log.w(TAG, "Could not read active notifications", error)
            return
        }
        LightSdkServer.onActiveNotificationsChanged(active)
    }

    private fun foregroundIfAlert(sbn: StatusBarNotification) {
        val notification = sbn.notification
        if (notification.extras.getString(LightConstants.EXTRA_ALERT_ID) == null) return

        val packageName = sbn.packageName
        val launch = notification.contentIntent ?: return
        // Only the tool's own intent, so a tool can't use this to open some other app.
        if (launch.creatorPackage != packageName) {
            Log.w(TAG, "Ignoring alert from $packageName with an intent created by ${launch.creatorPackage}")
            return
        }
        if (!hasAlertsCapability(packageName)) {
            Log.w(TAG, "Ignoring alert from $packageName, which does not declare the alerts capability")
            return
        }
        val settings = LightSdkServer.provideSdkSettings(this)
        if (!LightSdkServer.isPackageAllowed(settings.clientFilterLevel, this, packageName)) return
        if (!LightSdkServer.allowAlertForeground(packageName)) return

        sendAllowingBackgroundStart(launch)
    }

    @Suppress("DEPRECATION")
    private fun hasAlertsCapability(packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData?.getBoolean(LightConstants.CAPABILITY_ALERTS_MARKER) == true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    // The tool is in the background, so the launch only succeeds if this (foreground, system)
    // sender lends it permission to start an activity.
    private fun sendAllowingBackgroundStart(launch: PendingIntent) {
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
            .toBundle()
        try {
            launch.send(this, 0, null, null, null, null, options)
        } catch (error: PendingIntent.CanceledException) {
            Log.w(TAG, "Alert intent from ${launch.creatorPackage} was cancelled", error)
        }
    }

    companion object {
        private const val TAG = "LightNotificationListener"

        internal fun ensureEnabled(context: Context) {
            val component = ComponentName(context, LightNotificationListenerService::class.java)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.isNotificationListenerAccessGranted(component)) {
                grantAccess(manager, component)
            }
            requestRebind(component)
        }

        // System API, so not in the public SDK - same approach as grantRuntimePermission.
        // The three-argument form (with userSet) is tried first, then the older two-argument one.
        private fun grantAccess(manager: NotificationManager, component: ComponentName) {
            val granter = NotificationManager::class.java
            runCatching {
                granter.getMethod(
                    "setNotificationListenerAccessGranted",
                    ComponentName::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                ).invoke(manager, component, true, true)
            }.recoverCatching { error ->
                if (error !is NoSuchMethodException) throw error
                granter.getMethod(
                    "setNotificationListenerAccessGranted",
                    ComponentName::class.java,
                    Boolean::class.javaPrimitiveType,
                ).invoke(manager, component, true)
            }.onFailure {
                Log.e(TAG, "Could not grant notification listener access to $component", it)
            }
        }
    }
}
