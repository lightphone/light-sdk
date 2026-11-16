package com.thelightphone.sdk.server

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.thelightphone.sdk.shared.LightConstants

/**
 * Has two jobs:
 * 1. monitor all notifications so we can update the toolbox UI to show which tools have active ones
 * 2. look specifically for LIGHT_ALERT flavored notifs, which mean we should foreground that tool when posted,
 *    and again each time the screen turns off until the tool clears the alert's notification
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
        val stillAlerting = active
            .filter { it.notification.extras.getString(LightConstants.EXTRA_ALERT_ID) != null }
            .map { it.packageName }
        surfacedAlerts.keys.retainAll(stillAlerting.toSet())
        LightSdkServer.onActiveNotificationsChanged(active)
    }

    private fun foregroundIfAlert(sbn: StatusBarNotification) {
        val alertId = sbn.notification.extras.getString(LightConstants.EXTRA_ALERT_ID) ?: return

        val packageName = sbn.packageName
        if (!hasAlertsCapability(packageName)) {
            Log.w(TAG, "Ignoring alert from $packageName, which does not declare the alerts capability")
            return
        }
        val settings = LightSdkServer.provideSdkSettings(this)
        if (!LightSdkServer.isPackageAllowed(settings.clientFilterLevel, this, packageName)) return
        if (!LightSdkServer.allowAlertForeground(packageName)) return

        surfacedAlerts[packageName] = sbn.postTime
        startActivitySafely(this, alertLaunchIntent(packageName, alertId), packageName)
        wakeScreen()
    }

    // Woken here, not by the tool's activity, so that resurface() can bring the tool back
    // while the screen is off without waking it. ON_AFTER_RELEASE restarts the normal timeout.
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        getSystemService(PowerManager::class.java)
            .newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "LightSdk:alert",
            )
            .acquire(WAKE_DURATION_MS)
    }

    @Suppress("DEPRECATION")
    private fun hasAlertsCapability(packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData?.getBoolean(LightConstants.CAPABILITY_ALERTS_MARKER) == true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val TAG = "LightNotificationListener"
        private const val WAKE_DURATION_MS = 1_000L

        // Tools this listener brought forward for an alert, by when the alert was posted, kept
        // while the alert's notification is up. Touched only on the main thread.
        private val surfacedAlerts = mutableMapOf<String, Long>()

        /** The tool with the most recent alert that it has not cleared yet, if any. */
        internal fun latestAlertingTool(): String? =
            surfacedAlerts.maxByOrNull { it.value }?.key

        /**
         * Brings [packageName]'s LightActivity back to the front without waking the screen,
         * so the alert is what the user sees when the screen next turns on.
         */
        internal fun resurface(context: Context, packageName: String) {
            val launch = lightActivityIntent(packageName)
            startActivitySafely(context, launch, packageName)
        }

        // Built here rather than sending the notification's content intent, which the tool
        // controls and could point at another app. These always target the tool's own LightActivity.
        private fun alertLaunchIntent(packageName: String, alertId: String): Intent =
            lightActivityIntent(packageName)
                .putExtra(LightConstants.EXTRA_BRING_FORWARD, true)
                .putExtra(LightConstants.EXTRA_ALERT_ID, alertId)

        private fun lightActivityIntent(packageName: String): Intent = Intent()
            .setClassName(packageName, LightConstants.LIGHT_ACTIVITY_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun startActivitySafely(context: Context, launch: Intent, packageName: String) {
            try {
                context.startActivity(launch)
            } catch (error: ActivityNotFoundException) {
                Log.w(TAG, "Alert from $packageName has no ${LightConstants.LIGHT_ACTIVITY_CLASS}", error)
            } catch (error: SecurityException) {
                Log.w(TAG, "Could not launch alert activity for $packageName", error)
            }
        }

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
