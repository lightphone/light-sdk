package com.thelightphone.sdk.audio

import android.content.Context
import android.content.pm.PackageManager

/**
 * Written by the Light Gradle plugin for tools that declare the capability in
 * `lighttool.toml`. Nothing else can contribute it — unlike the foreground
 * service permissions, which any transitive dependency may also declare.
 */
private const val DETACHED_AUDIO_MARKER = "com.thelightphone.sdk.CAPABILITY_DETACHED_AUDIO"

@Suppress("DEPRECATION")
internal fun Context.requireDetachedAudioCapability() {
    val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    if (appInfo.metaData?.getBoolean(DETACHED_AUDIO_MARKER) != true) {
        throw LightAudioPlayerException(
            """
            Detached audio requires this lighttool.toml capability:
            capabilities = ["detached-audio"]
            """.trimIndent()
        )
    }
}
