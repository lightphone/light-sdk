package com.thelightphone.sdk.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.source.MediaSource

/**
 * Supplies the media3 `MediaSource.Factory` the SDK's player reads through.
 *
 * A [LightMediaCache] describes where bytes are kept, which is enough for audio
 * media3 can fetch itself. Audio it cannot fetch — anything that has to be
 * decrypted, unwrapped, or pulled out of a session the tool owns — cannot be
 * described as data at all, so the tool supplies the input pipeline instead.
 *
 * The SDK still owns the output half. Audio attributes, focus, the media
 * session, and the queue stay with the player the SDK builds, and a factory
 * cannot reach them: it is asked only where audio comes from.
 *
 * This runs while the player is being constructed, and in detached playback
 * that construction happens inside [LightAudioService]. The factory therefore
 * outlives the screen that installed it, so it must not capture UI state.
 */
@UnstableApi
fun interface LightMediaSourceFactory {
    fun create(env: LightMediaEnv): MediaSource.Factory
}

/**
 * The media3 pieces the SDK will hand a [LightMediaSourceFactory].
 *
 * Tools have no `Context`, so they cannot open a cache or build a data source
 * that reads local files. Both arrive here already built, rooted in the tool's
 * own storage.
 *
 * @property caches every cache passed to `newPlayer`, opened and keyed by
 *   [LightMediaCache.name]. Shared process-wide, so a factory that writes here
 *   fills the same store playback reads from
 * @property dataSourceFactory reads `file://`, `asset://`, `content://`, and
 *   HTTP, with no caching of its own. Wrap it to add caching, or ignore it
 *   entirely when the audio does not come from any of those
 */
@UnstableApi
class LightMediaEnv internal constructor(
    val caches: Map<String, Cache>,
    val dataSourceFactory: DataSource.Factory,
)
