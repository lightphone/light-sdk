package com.thelightphone.sdk

import androidx.annotation.Keep
import com.thelightphone.sdk.shared.LightServerData
import com.thelightphone.toolmanager.ClientToolManifest
import kotlinx.coroutines.flow.StateFlow

@Keep
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class EntryPoint

interface LightEntryPoint {
    suspend fun onToolCreate(serverData: StateFlow<LightServerData?>): Unit = Unit

    suspend fun onPushNotification(data: ByteArray): Unit = Unit

    /**
     * Called when an alert scheduled with [LightNotifications.schedule] arrives, after the SDK
     * has brought the tool forward. Return true to drop the alert, or false to keep it so a
     * reboot delivers it again until [LightNotifications.cancel] drops it.
     */
    suspend fun onAlert(alert: LightAlert): Boolean = true

    fun getToolManagerManifest(): ClientToolManifest? = null

    suspend fun onToolManagerDataUpdate(): Unit = Unit

    val enablePushNotifications: Boolean
        get() = false


    // See: https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean)
    // When LightOS moves to an external tool that's already in memory, it shows a system screenshot by default
    // because there are no transition animations. here we override that to not show a screenshot (so it shows nothing instead).
    // This feels more natural for tools that want to show specific UI immediately when foregrounded.
    // You probably want to leave this as "false" unless you're building for alternate devices.
    val enableRecentsScreenshots: Boolean
        get() = false
}
