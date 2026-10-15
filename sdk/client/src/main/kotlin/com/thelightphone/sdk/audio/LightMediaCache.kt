package com.thelightphone.sdk.audio

/**
 * A named on-disk store of streamed media bytes, kept under the tool's own
 * cache storage.
 *
 * The SDK opens the store, wires it into the player it builds, and shares one
 * per directory across the process. Bytes are filed under [LightAudioItem.id],
 * so an item whose location changes still finds what it cached. The bytes read
 * longest ago are discarded to stay under [sizeBytes]. A name opened again in
 * the same process must use the same [sizeBytes].
 *
 * DASH segments are filed under their own URLs, so re-resolved DASH URLs do not
 * reuse what the old ones cached.
 *
 * @property name directory name: letters, digits, `-`, and `_` only
 * @property sizeBytes the most the store may hold
 */
data class LightMediaCache(
    val name: String,
    val sizeBytes: Long,
) {
    init {
        require(name.isNotBlank()) { "Cache name must not be blank" }
        require(name.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Cache name must contain only letters, digits, '-', or '_', but was '$name'"
        }
        require(sizeBytes > 0) { "Cache size must be positive, but was $sizeBytes" }
    }
}
