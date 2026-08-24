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
 * Attached players build here, and so does [LightAudioService] in `onCreate`.
 * Caches and [sourceFactory] have to be settled by now: where a player reads
 * bytes from is a constructor argument, so unlike [LightAudioUsage] it cannot
 * be applied to a player that already exists.
 *
 * A [sourceFactory] replaces the SDK's own read path rather than layering onto
 * it. The tool is saying media3 cannot fetch this audio unaided, so wrapping
 * its answer in the SDK's HTTP pipeline would only put a fetcher it already
 * rejected back in front of it. The caches still open, and the factory gets
 * them, so opting out of the read path does not mean opting out of storage.
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
 * Puts the tool's caches between the network and the player, consulted in the
 * order the tool listed them and only then falling through to the network.
 *
 * The caches wrap HTTP alone, and [DefaultDataSource] wraps them, because it
 * serves `file://`, `asset://`, and `content://` itself and delegates only what
 * it cannot serve. Bytes already on the device therefore never get copied into
 * a cache to sit beside themselves.
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
            // A cache that cannot serve a read should cost latency, not playback.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (cache.eviction is LightCacheEviction.Never) {
            // Null sink: the tool fills this one deliberately, so streaming
            // must not quietly grow a store that never evicts.
            factory.setCacheWriteDataSinkFactory(null)
        }
        network = factory
    }
    return DefaultDataSource.Factory(context, network)
}

/**
 * The caches this tool process has open, one per directory.
 *
 * media3 permits a single [SimpleCache] per directory per process and throws on
 * the second, so opening must be atomic rather than merely usually-fine: an
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

/**
 * A size-limited cache lives in cache storage, which the platform may reclaim
 * under pressure — the same bargain the tool already made by bounding it. A
 * cache that never evicts lives in files storage, where nothing but the tool
 * removes it.
 */
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
