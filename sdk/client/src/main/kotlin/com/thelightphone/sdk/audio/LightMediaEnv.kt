package com.thelightphone.sdk.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Sandboxed media3 primitives rooted in the tool's private storage.
 *
 * This type vends the helpers needed to configure streaming playback. Named caches
 * are shared for the tool process. [LightAudioPlayer.release] does not release
 * them.
 *
 * @property filesDir the tool's private files directory
 * @property cacheDir the tool's private cache directory
 */
@UnstableApi
class LightMediaEnv internal constructor(context: Context) {
    private val appContext = context.applicationContext
    val filesDir: File = context.filesDir
    val cacheDir: File = context.cacheDir

    private val databaseProviders = mutableMapOf<String, DatabaseProvider>()

    /** Returns a shared [DatabaseProvider] for media cache indices. */
    fun databaseProvider(): DatabaseProvider =
        databaseProviders.getOrPut(DEFAULT_DB) {
            StandaloneDatabaseProvider(appContext)
        }

    /**
     * Opens or returns a named [Cache] under tool storage.
     *
     * When [maxBytes] is `null`, the cache is pinned under [filesDir] with a
     * [NoOpCacheEvictor]. Otherwise an LRU cache under [cacheDir] is capped at
     * [maxBytes]. One [SimpleCache] instance is shared per cache directory for
     * the tool process. We are protecting against multiple instances in one cache directory.
     */
    fun cache(name: String, maxBytes: Long? = null): Cache {
        val (directory, evictor) = if (maxBytes == null) {
            File(filesDir, "$CACHE_DIR_PREFIX/$name").also { it.mkdirs() } to NoOpCacheEvictor()
        } else {
            File(cacheDir, "$CACHE_DIR_PREFIX/$name").also { it.mkdirs() } to
                LeastRecentlyUsedCacheEvictor(maxBytes)
        }
        val pathKey = directory.canonicalPath
        return cachesByPath.getOrPut(pathKey) {
            SimpleCache(directory, evictor, databaseProvider())
        }
    }

    /** Wraps [upstream] in a platform [DefaultDataSource.Factory]. */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        DefaultDataSource.Factory(appContext, upstream)

    internal companion object {
        private val envs = ConcurrentHashMap<Context, LightMediaEnv>()
        private val cachesByPath = ConcurrentHashMap<String, Cache>()

        internal fun forContext(context: Context): LightMediaEnv {
            val appContext = context.applicationContext
            return envs.getOrPut(appContext) { LightMediaEnv(appContext) }
        }
    }
}

private const val DEFAULT_DB = "default"
private const val CACHE_DIR_PREFIX = "light-media-cache"
