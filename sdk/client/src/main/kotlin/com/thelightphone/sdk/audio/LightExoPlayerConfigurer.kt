package com.thelightphone.sdk.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

/**
 * Configures an [ExoPlayer] for [LightAudio.newPlayer] without exposing Android
 * [Context] to tool source (which the Light SDK plugin blocks from importing).
 *
 * Set media-source, cache, priority, and load-control options on [builder], then
 * return [ExoPlayer.Builder.build]. The SDK adopts that instance and re-asserts
 * audio attributes, focus, and lifecycle.
 */
@UnstableApi
class LightExoPlayerConfigurer internal constructor(
    val builder: ExoPlayer.Builder,
    private val appContext: Context,
) {
    /** Application files directory — durable caches and offline pins. */
    val filesDir: File get() = appContext.filesDir

    /** Application cache directory — evictable streaming LRUs. */
    val cacheDir: File get() = appContext.cacheDir

    /** Default media-source factory for file, asset, and http(s) URIs. */
    fun defaultMediaSourceFactory(): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(appContext)

    /** Default upstream data-source factory (file / asset / http). */
    fun defaultDataSourceFactory(): DefaultDataSource.Factory =
        DefaultDataSource.Factory(appContext)

    /** Media3 database used by [androidx.media3.datasource.cache.SimpleCache]. */
    fun databaseProvider(): DatabaseProvider =
        StandaloneDatabaseProvider(appContext)
}
