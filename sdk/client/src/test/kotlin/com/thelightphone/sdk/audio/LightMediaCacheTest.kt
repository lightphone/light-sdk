package com.thelightphone.sdk.audio

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
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

/** Never invoked: these tests only care whether a factory was supplied. */
@OptIn(UnstableApi::class)
private val someSourceFactory = LightMediaSourceFactory { error("not built in this test") }

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
        validateMediaCaches(emptyList(), hasCustomSource = false)
        validateMediaCaches(listOf(streamCache, savedCache), hasCustomSource = false)
        validateMediaCaches(listOf(savedCache), hasCustomSource = false)
    }

    @Test
    fun cachesCannotShareANameOrBothClaimTheStreamedBytes() {
        assertFailsWith<IllegalArgumentException> {
            validateMediaCaches(listOf(savedCache, savedCache), hasCustomSource = false)
        }
        assertFailsWith<IllegalArgumentException> {
            validateMediaCaches(
                listOf(streamCache, streamCache.copy(name = "other")),
                hasCustomSource = false,
            )
        }
    }

    /**
     * The single-writer rule speaks for the SDK's read path. A tool supplying
     * its own decides for itself which stores it fills.
     */
    @Test
    fun aCustomSourceMayFillSeveralSizeLimitedCaches() {
        validateMediaCaches(
            listOf(streamCache, streamCache.copy(name = "other")),
            hasCustomSource = true,
        )
    }

    @Test
    fun namesStayDistinctEvenWithACustomSource() {
        assertFailsWith<IllegalArgumentException> {
            validateMediaCaches(listOf(savedCache, savedCache), hasCustomSource = true)
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

class DetachedSourceFactoryTest {
    /**
     * Two factories cannot be compared, so a live session accepts none rather
     * than pretending to check the one it is offered.
     */
    @Test
    fun aLiveSessionAcceptsAReconnectOnlyWithoutAFactory() {
        assertTrue(isDetachedSourceFactoryCompatible(null, someSourceFactory))
        assertTrue(isDetachedSourceFactoryCompatible(true, null))
        assertTrue(isDetachedSourceFactoryCompatible(false, null))
        assertFalse(isDetachedSourceFactoryCompatible(true, someSourceFactory))
        assertFalse(isDetachedSourceFactoryCompatible(false, someSourceFactory))
    }

    @Test
    fun stagedFactorySurvivesUntilTheServiceBuildsItsPlayer() {
        val state = DetachedSessionState()

        state.stageSourceFactory(someSourceFactory)

        assertEquals(someSourceFactory, state.stagedSourceFactory())
        assertNull(state.activeSourceFactoryPresence())
    }

    /** The lambda is dropped once used; only the fact of it is worth keeping. */
    @Test
    fun adoptingRecordsPresenceAndReleasesTheFactory() {
        val state = DetachedSessionState()
        state.stageSourceFactory(someSourceFactory)

        state.adoptSourceFactory(state.stagedSourceFactory() != null)

        assertEquals(true, state.activeSourceFactoryPresence())
        assertNull(state.stagedSourceFactory())
    }

    @Test
    fun aRevivedSessionRecordsThatItWasBuiltWithoutAFactory() {
        val state = DetachedSessionState()

        state.adoptSourceFactory(state.stagedSourceFactory() != null)

        assertEquals(false, state.activeSourceFactoryPresence())
        assertFalse(isDetachedSourceFactoryCompatible(state.activeSourceFactoryPresence(), someSourceFactory))
    }

    @Test
    fun clearingTheSessionLetsTheNextHandleBringAFactoryAgain() {
        val state = DetachedSessionState()
        state.adoptSourceFactory(true)

        state.clearSession()

        assertNull(state.activeSourceFactoryPresence())
        assertTrue(isDetachedSourceFactoryCompatible(state.activeSourceFactoryPresence(), someSourceFactory))
    }
}
