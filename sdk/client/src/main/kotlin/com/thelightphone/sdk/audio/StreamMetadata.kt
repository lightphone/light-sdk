@file:OptIn(UnstableApi::class)

package com.thelightphone.sdk.audio

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.icy.IcyInfo

/** Latest ICY entry in [this], or `null` when it carries no title or URL. */
internal fun Metadata.toLightStreamMetadata(): LightStreamMetadata? {
    for (i in length() - 1 downTo 0) {
        val icy = get(i) as? IcyInfo ?: continue
        if (icy.title != null || icy.url != null) {
            return LightStreamMetadata(title = icy.title, url = icy.url)
        }
    }
    return null
}

// Detached playback forwards stream metadata to controllers as session extras,
// since media3 does not pass onMetadata through a MediaController.
internal fun LightStreamMetadata.toSessionExtras(): Bundle = Bundle().apply {
    putString(EXTRA_STREAM_TITLE, title)
    putString(EXTRA_STREAM_URL, url)
}

internal fun Bundle.toLightStreamMetadata(): LightStreamMetadata? {
    val title = getString(EXTRA_STREAM_TITLE)
    val url = getString(EXTRA_STREAM_URL)
    return if (title == null && url == null) null else LightStreamMetadata(title, url)
}

private const val EXTRA_STREAM_TITLE = "com.thelightphone.sdk.audio.STREAM_TITLE"
private const val EXTRA_STREAM_URL = "com.thelightphone.sdk.audio.STREAM_URL"
