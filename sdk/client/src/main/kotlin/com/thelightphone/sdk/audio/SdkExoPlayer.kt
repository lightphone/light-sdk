package com.thelightphone.sdk.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds the player the SDK owns, in the one place both playback modes reach.
 *
 * A [sourceFactory] replaces the SDK HTTP path. Requested caches still open
 * and are handed to the factory.
 */
@OptIn(UnstableApi::class)
internal fun buildSdkExoPlayer(
    context: Context,
    usage: LightAudioUsage,
    caches: List<LightMediaCache>,
    sourceFactory: LightMediaSourceFactory? = null,
): ExoPlayer {
    val builder = ExoPlayer.Builder(context)
    val opened = caches.associate { it.name to mediaCacheStore.open(context, it) }
    when {
        sourceFactory != null -> builder.setMediaSourceFactory(
            sourceFactory.create(LightMediaEnv(opened, platformDataSourceFactory(context)))
        )
        caches.isNotEmpty() -> builder.setMediaSourceFactory(
            DefaultMediaSourceFactory(cachingDataSourceFactory(context, caches, opened))
        )
    }
    return builder.build().apply {
        setAudioAttributes(usage.toMedia3AudioAttributes(), true)
    }
}

/** Everything the platform can read without help, and nothing cached. */
@OptIn(UnstableApi::class)
private fun platformDataSourceFactory(context: Context): DataSource.Factory =
    DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())

/**
 * Caches wrap HTTP only; [DefaultDataSource] wraps them so local files are
 * not copied into the cache.
 */
@OptIn(UnstableApi::class)
private fun cachingDataSourceFactory(
    context: Context,
    caches: List<LightMediaCache>,
    opened: Map<String, Cache>,
): DataSource.Factory {
    var network: DataSource.Factory = DefaultHttpDataSource.Factory()
    for (cache in caches.asReversed()) {
        val factory = CacheDataSource.Factory()
            .setCache(requireNotNull(opened[cache.name]))
            .setUpstreamDataSourceFactory(network)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (cache.eviction is LightCacheEviction.Never) {
            factory.setCacheWriteDataSinkFactory(null)
        }
        network = factory
    }
    return DefaultDataSource.Factory(context, network)
}

/**
 * One [SimpleCache] per directory per process. Opening is atomic because an
 * attached player and [LightAudioService] can reach for the same cache at once.
 */
@OptIn(UnstableApi::class)
internal class MediaCacheStore {
    private val cachesByPath = ConcurrentHashMap<String, Cache>()
    private val databaseProviders = ConcurrentHashMap<Context, DatabaseProvider>()

    fun open(context: Context, spec: LightMediaCache): Cache {
        val appContext = context.applicationContext
        val directory = spec.directoryIn(appContext)
        return cachesByPath.computeIfAbsent(directory.canonicalPath) {
            SimpleCache(directory, spec.eviction.toEvictor(), databaseProvider(appContext))
        }
    }

    private fun databaseProvider(appContext: Context): DatabaseProvider =
        databaseProviders.computeIfAbsent(appContext) { StandaloneDatabaseProvider(it) }
}

internal val mediaCacheStore = MediaCacheStore()

/** LRU caches live in `cacheDir`; never-evict caches live in `filesDir`. */
private fun LightMediaCache.directoryIn(context: Context): File {
    val root = when (eviction) {
        is LightCacheEviction.LeastRecentlyUsed -> context.cacheDir
        LightCacheEviction.Never -> context.filesDir
    }
    return File(File(root, CACHE_ROOT_DIR), name).apply { mkdirs() }
}

@OptIn(UnstableApi::class)
private fun LightCacheEviction.toEvictor() = when (this) {
    is LightCacheEviction.LeastRecentlyUsed -> LeastRecentlyUsedCacheEvictor(maxBytes)
    LightCacheEviction.Never -> NoOpCacheEvictor()
}

private const val CACHE_ROOT_DIR = "light-media-cache"
