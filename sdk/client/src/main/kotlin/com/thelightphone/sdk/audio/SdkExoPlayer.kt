package com.thelightphone.sdk.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds the player the SDK owns, in the one place both playback modes reach.
 *
 * A requested cache wraps HTTP only; [DefaultDataSource] wraps it so local
 * files are not copied into the cache.
 *
 * [usage] applies to items without their own [LightAudioItem.usage]. The
 * player re-applies audio attributes as the current item's usage changes and
 * reports each change to [onUsageChanged].
 */
@OptIn(UnstableApi::class)
internal fun buildSdkExoPlayer(
    context: Context,
    usage: LightAudioUsage,
    cache: LightMediaCache?,
    onUsageChanged: (LightAudioUsage) -> Unit = {},
): ExoPlayer {
    val builder = ExoPlayer.Builder(context)
    if (cache != null) {
        val network = CacheDataSource.Factory()
            .setCache(mediaCacheStore.open(context, cache))
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        builder.setMediaSourceFactory(
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, network))
        )
    }
    builder.setSeekBackIncrementMs(SEEK_INCREMENT_MS).setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
    return builder.build().apply {
        setAudioAttributes(usage.toMedia3AudioAttributes(), true)
        addListener(ItemUsageFollower(this, usage, onUsageChanged))
    }
}

/**
 * Keeps the player's audio attributes on the current item's usage. A timeline
 * change covers replacing the current item, which is not a transition.
 */
private class ItemUsageFollower(
    private val player: ExoPlayer,
    private val playerUsage: LightAudioUsage,
    private val onUsageChanged: (LightAudioUsage) -> Unit,
) : Player.Listener {
    private var applied = playerUsage

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = follow()

    override fun onTimelineChanged(timeline: Timeline, reason: Int) = follow()

    private fun follow() {
        val usage = effectiveUsage(player.currentMediaItem?.itemUsage(), playerUsage)
        if (usage == applied) return
        applied = usage
        player.setAudioAttributes(usage.toMedia3AudioAttributes(), true)
        onUsageChanged(usage)
    }
}

/**
 * One [SimpleCache] per directory per process. Opening is atomic because an
 * attached player and [LightAudioService] can reach for the same cache at once.
 */
@OptIn(UnstableApi::class)
internal class MediaCacheStore {
    private val sizes = CacheSizeClaims()
    private val cachesByPath = ConcurrentHashMap<String, Cache>()
    private var databaseProvider: DatabaseProvider? = null

    /** Fixes [spec]'s size for the process without opening it. */
    fun claim(spec: LightMediaCache) {
        sizes.claim(spec)
    }

    fun open(context: Context, spec: LightMediaCache): Cache {
        claim(spec)
        val appContext = context.applicationContext
        val directory = spec.directoryIn(appContext)
        return cachesByPath.computeIfAbsent(directory.canonicalPath) {
            SimpleCache(directory, LeastRecentlyUsedCacheEvictor(spec.sizeBytes), databaseProvider(appContext))
        }
    }

    @Synchronized
    private fun databaseProvider(appContext: Context): DatabaseProvider =
        databaseProvider ?: StandaloneDatabaseProvider(appContext).also { databaseProvider = it }
}

/** A cache's size is fixed by the first claim on its name in the process. */
internal class CacheSizeClaims {
    private val sizesByName = ConcurrentHashMap<String, Long>()

    fun claim(spec: LightMediaCache) {
        val claimed = sizesByName.putIfAbsent(spec.name, spec.sizeBytes) ?: return
        require(claimed == spec.sizeBytes) {
            "Cache '${spec.name}' is already in use with $claimed bytes; requested ${spec.sizeBytes}"
        }
    }
}

internal val mediaCacheStore = MediaCacheStore()

private fun LightMediaCache.directoryIn(context: Context): File =
    File(File(context.cacheDir, CACHE_ROOT_DIR), name).apply { mkdirs() }

private const val CACHE_ROOT_DIR = "light-media-cache"
