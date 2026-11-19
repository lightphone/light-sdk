package com.thelightphone.sdk.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.PersistableBundle
import android.util.Log

private const val TAG = "LightClipboard"

// SystemUI (Android 13+) skips its copy popup for clips carrying this extra, but only when the
// clip's source is the shell (or the device is an emulator). See SystemUI's ClipboardListener
private const val EXTRA_SUPPRESS_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"
private const val SHELL_PACKAGE = "com.android.shell"
private const val PERMISSION_SET_CLIP_SOURCE = "android.permission.SET_CLIP_SOURCE"

/**
 * Puts [text] on the clipboard without SystemUI's copy popup. Needs to be run within a system app
 */
internal fun Context.setClipboardTextQuietly(text: String, label: String?) {
    val clipboard = getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_SUPPRESS_OVERLAY, true)
        }
    }
    val attributed = checkSelfPermission(PERMISSION_SET_CLIP_SOURCE) == PERMISSION_GRANTED &&
        runCatching {
            // a @SystemApi, so it's missing from the public SDK
            ClipboardManager::class.java
                .getMethod("setPrimaryClipAsPackage", ClipData::class.java, String::class.java)
                .invoke(clipboard, clip, SHELL_PACKAGE)
        }.onFailure {
            Log.w(TAG, "setPrimaryClipAsPackage failed, copying with the system popup", it)
        }.isSuccess
    // still copy the text, the system popup will just show
    if (!attributed) clipboard.setPrimaryClip(clip)
}
