package com.thelightphone.sdk.audio

/**
 * A named on-disk store of media bytes, kept under the tool's own storage.
 *
 * A cache is described rather than handed over: the SDK opens the store, wires
 * it into the player it builds, and shares one store per directory across the
 * tool process. Two players naming the same cache read and write the same
 * bytes, and a cache outlives every player that fills it.
 *
 * Caches are named so that bytes can be found again. Bytes are filed under
 * [LightAudioItem.id], so an item whose location changes still finds what it
 * cached — which is what makes a cache useful for audio behind expiring URLs.
 *
 * @property name identifies the store and its directory. Letters, digits, `-`,
 *   and `_` only, since it names a directory
 * @property eviction what the store does when it runs out of room
 */
data class LightMediaCache(
    val name: String,
    val eviction: LightCacheEviction,
) {
    init {
        require(name.isNotBlank()) { "Cache name must not be blank" }
        require(name.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Cache name must contain only letters, digits, '-', or '_', but was '$name'"
        }
    }
}

/** What a [LightMediaCache] does when it runs out of room. */
sealed interface LightCacheEviction {
    /**
     * Discards the bytes read longest ago to stay under [maxBytes].
     *
     * This is where the SDK writes what it streams, so a player with no such
     * cache caches nothing the SDK fetched.
     */
    data class LeastRecentlyUsed(val maxBytes: Long) : LightCacheEviction {
        init {
            require(maxBytes > 0) { "Cache size must be positive, but was $maxBytes" }
        }
    }

    /**
     * Discards nothing, leaving the tool to decide what to delete and when.
     *
     * The SDK never writes here: streaming into a store that never evicts would
     * pin every byte played, which is a decision only the tool can make. A
     * [LightMediaSourceFactory] can write here, since a tool supplying its own
     * pipeline is already deciding what to keep.
     */
    data object Never : LightCacheEviction
}

/**
 * Rejects cache lists that cannot mean one thing.
 *
 * Names have to be distinct however the caches are used, since a name is a
 * directory and two of them would be one store under two descriptions.
 *
 * The single-writer rule is narrower. It exists because the SDK's own read path
 * has to pick one cache to stream into, and two size-limited caches leave that
 * unsaid. A [hasCustomSource] tool does its own writing and can reasonably fill
 * several stores, so the rule would only be the SDK legislating a decision it
 * no longer makes.
 */
internal fun validateMediaCaches(caches: List<LightMediaCache>, hasCustomSource: Boolean) {
    val names = caches.map(LightMediaCache::name)
    require(names.size == names.toSet().size) {
        "Each cache must have a distinct name, but got $names"
    }
    require(
        hasCustomSource ||
            caches.count { it.eviction is LightCacheEviction.LeastRecentlyUsed } <= 1
    ) {
        "At most one cache may be size-limited, since that is the one written while streaming"
    }
}
