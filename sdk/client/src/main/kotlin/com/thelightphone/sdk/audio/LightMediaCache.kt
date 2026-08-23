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
     * This is where newly streamed bytes land, so a player with no such cache
     * caches nothing it streams.
     */
    data class LeastRecentlyUsed(val maxBytes: Long) : LightCacheEviction {
        init {
            require(maxBytes > 0) { "Cache size must be positive, but was $maxBytes" }
        }
    }

    /**
     * Discards nothing, leaving the tool to decide what to delete and when.
     *
     * Playback only ever reads such a store, so it holds what the tool put
     * there deliberately rather than whatever was played recently.
     */
    data object Never : LightCacheEviction
}

/**
 * Rejects cache lists that cannot mean one thing.
 *
 * Reads consult caches in the order given, so the list is already an ordering.
 * What it must not also be is ambiguous about where bytes are written, which
 * two size-limited caches would make it.
 */
internal fun validateMediaCaches(caches: List<LightMediaCache>) {
    val names = caches.map(LightMediaCache::name)
    require(names.size == names.toSet().size) {
        "Each cache must have a distinct name, but got $names"
    }
    require(caches.count { it.eviction is LightCacheEviction.LeastRecentlyUsed } <= 1) {
        "At most one cache may be size-limited, since that is the one written while streaming"
    }
}
