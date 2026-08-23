package com.thelightphone.sdk.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val streamCache = LightMediaCache(
    name = "stream",
    eviction = LightCacheEviction.LeastRecentlyUsed(maxBytes = 256L * 1024 * 1024),
)
private val savedCache = LightMediaCache(name = "saved", eviction = LightCacheEviction.Never)

class LightMediaCacheTest {
    @Test
    fun cacheNameHasToBeUsableAsADirectoryName() {
        assertFailsWith<IllegalArgumentException> {
            LightMediaCache(" ", LightCacheEviction.Never)
        }
        assertFailsWith<IllegalArgumentException> {
            LightMediaCache("../escape", LightCacheEviction.Never)
        }
        assertFailsWith<IllegalArgumentException> {
            LightMediaCache("nested/name", LightCacheEviction.Never)
        }
        assertEquals("tidal_stream-1", LightMediaCache("tidal_stream-1", LightCacheEviction.Never).name)
    }

    @Test
    fun sizeLimitedCacheNeedsRoomToWorkWith() {
        assertFailsWith<IllegalArgumentException> { LightCacheEviction.LeastRecentlyUsed(0) }
        assertFailsWith<IllegalArgumentException> { LightCacheEviction.LeastRecentlyUsed(-1) }
    }

    @Test
    fun readThroughOrderAcceptsOneWritableCacheAheadOfSavedOnes() {
        validateMediaCaches(emptyList())
        validateMediaCaches(listOf(streamCache, savedCache))
        validateMediaCaches(listOf(savedCache))
    }

    @Test
    fun cachesCannotShareANameOrBothClaimTheStreamedBytes() {
        assertFailsWith<IllegalArgumentException> {
            validateMediaCaches(listOf(savedCache, savedCache))
        }
        assertFailsWith<IllegalArgumentException> {
            validateMediaCaches(listOf(streamCache, streamCache.copy(name = "other")))
        }
    }
}

class DetachedSessionCacheTest {
    @Test
    fun cacheCompatibilityAcceptsFreshSessionsAndMatchingRequestsOnly() {
        assertTrue(isDetachedCacheCompatible(activeCaches = null, listOf(streamCache)))
        assertTrue(isDetachedCacheCompatible(listOf(streamCache), listOf(streamCache)))
        assertFalse(isDetachedCacheCompatible(emptyList(), listOf(streamCache)))
        assertFalse(
            isDetachedCacheCompatible(listOf(streamCache, savedCache), listOf(savedCache, streamCache)),
        )
    }

    /**
     * The service reads staged caches in `onCreate`, which is why they are left
     * behind before a controller is ever built.
     */
    @Test
    fun stagedCachesSurviveUntilTheServiceBuildsItsPlayer() {
        val state = DetachedSessionState()

        state.stageCaches(listOf(streamCache))

        assertEquals(listOf(streamCache), state.stagedCaches())
        assertNull(state.activeCaches())
    }

    @Test
    fun adoptingCachesPublishesThemAndConsumesTheStaging() {
        val state = DetachedSessionState()
        state.stageCaches(listOf(streamCache))

        state.adoptCaches(state.stagedCaches())

        assertEquals(listOf(streamCache), state.activeCaches())
        assertEquals(emptyList(), state.stagedCaches())
    }

    @Test
    fun aRevivedSessionRecordsThatItWasBuiltWithoutCaches() {
        val state = DetachedSessionState()

        state.adoptCaches(state.stagedCaches())

        assertEquals(emptyList(), state.activeCaches())
        assertFalse(isDetachedCacheCompatible(state.activeCaches(), listOf(streamCache)))
    }

    @Test
    fun clearingTheSessionLetsTheNextHandleChooseCachesAgain() {
        val state = DetachedSessionState()
        state.adoptCaches(listOf(streamCache))

        state.clearSession()

        assertNull(state.activeCaches())
        assertTrue(isDetachedCacheCompatible(state.activeCaches(), listOf(savedCache)))
    }
}
