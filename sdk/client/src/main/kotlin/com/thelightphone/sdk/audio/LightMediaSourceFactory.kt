package com.thelightphone.sdk.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.source.MediaSource

/**
 * Supplies the media3 `MediaSource.Factory` the SDK's player reads through.
 *
 * Use this for audio media3 cannot fetch itself (decrypt, a session the tool
 * owns). It replaces the SDK's HTTP read path and cannot reach sink, focus,
 * session, or queue.
 *
 * Runs at player construction, which in detached playback is inside
 * [LightAudioService], so the factory must not capture UI state.
 */
@UnstableApi
fun interface LightMediaSourceFactory {
    fun create(env: LightMediaEnv): MediaSource.Factory
}

/**
 * The media3 pieces the SDK will hand a [LightMediaSourceFactory].
 *
 * Tools have no `Context`, so caches and a platform data source are already
 * built here, rooted in the tool's own storage.
 *
 * @property caches caches passed to `newPlayer`, opened and keyed by
 *   [LightMediaCache.name]
 * @property dataSourceFactory `file://`, `asset://`, `content://`, and HTTP,
 *   with no caching. Wrap it, or ignore it when the audio comes from elsewhere
 */
@UnstableApi
class LightMediaEnv internal constructor(
    val caches: Map<String, Cache>,
    val dataSourceFactory: DataSource.Factory,
)
