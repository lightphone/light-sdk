package com.thelightphone.sdk.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val streamCache = LightMediaCache(name = "stream", sizeBytes = 256L * 1024 * 1024)

class LightMediaCacheTest {
    @Test
    fun cacheNameHasToBeUsableAsADirectoryName() {
        assertFailsWith<IllegalArgumentException> { LightMediaCache(" ", SIZE) }
        assertFailsWith<IllegalArgumentException> { LightMediaCache("../escape", SIZE) }
        assertFailsWith<IllegalArgumentException> { LightMediaCache("nested/name", SIZE) }
        assertEquals("tidal_stream-1", LightMediaCache("tidal_stream-1", SIZE).name)
    }

    @Test
    fun cacheNeedsRoomToWorkWith() {
        assertFailsWith<IllegalArgumentException> { LightMediaCache("stream", 0) }
        assertFailsWith<IllegalArgumentException> { LightMediaCache("stream", -1) }
    }

    @Test
    fun reclaimingANameRequiresTheSameSize() {
        val claims = CacheSizeClaims()
        claims.claim(LightMediaCache("stream", SIZE))
        claims.claim(LightMediaCache("stream", SIZE))
        claims.claim(LightMediaCache("other", SIZE * 2))
        assertFailsWith<IllegalArgumentException> {
            claims.claim(LightMediaCache("stream", SIZE * 2))
        }
    }

    private companion object {
        const val SIZE = 1024L
    }
}

class DetachedSessionCacheTest {
    private val detachedSpec = LightPlayerSpec(
        playback = LightAudioPlayback.Detached,
        cache = streamCache,
    )

    @Test
    fun specCompatibilityAcceptsFreshAndMatchingSessionsOnly() {
        assertTrue(isDetachedSpecCompatible(null, detachedSpec))
        assertTrue(isDetachedSpecCompatible(detachedSpec, detachedSpec.copy()))
        assertFalse(isDetachedSpecCompatible(detachedSpec, detachedSpec.copy(cache = streamCache.copy(name = "other"))))
        assertFalse(isDetachedSpecCompatible(detachedSpec, detachedSpec.copy(usage = LightAudioUsage.Alarm)))
    }

    @Test
    fun stagedSpecOutlivesTheSessionItStarted() {
        val state = DetachedSessionState()
        state.stageSpec(detachedSpec)
        assertEquals(detachedSpec, state.stagedSpec())
        state.adoptSpec(state.stagedSpec())
        assertEquals(detachedSpec, state.activeSpec())
        state.clearSession()
        assertNull(state.activeSpec())
        assertEquals(detachedSpec, state.stagedSpec())
    }
}

class AdmitDetachedPlayerTest {
    private val detached = LightPlayerSpec(playback = LightAudioPlayback.Detached, cache = streamCache)

    @Test
    fun admissionStagesTheSpecAndTakesTheHandle() {
        val state = DetachedSessionState()

        admitDetachedPlayer(detached, state, CacheSizeClaims()::claim)

        assertTrue(state.isHandleOpen())
        assertEquals(detached, state.stagedSpec())
    }

    @Test
    fun cacheSizeConflictFailsBeforeAnythingIsStaged() {
        val state = DetachedSessionState()
        val claims = CacheSizeClaims().apply { claim(streamCache.copy(sizeBytes = 1024L)) }

        assertFailsWith<IllegalArgumentException> { admitDetachedPlayer(detached, state, claims::claim) }

        assertFalse(state.isHandleOpen())
        assertEquals(LightPlayerSpec(playback = LightAudioPlayback.Detached), state.stagedSpec())
    }

    @Test
    fun incompatibleLiveSessionFailsWithoutTakingTheHandle() {
        val state = DetachedSessionState().apply { adoptSpec(detached) }

        assertFailsWith<LightAudioPlayerException> {
            admitDetachedPlayer(detached.copy(usage = LightAudioUsage.Alarm), state, CacheSizeClaims()::claim)
        }

        assertFalse(state.isHandleOpen())
        assertEquals(LightPlayerSpec(playback = LightAudioPlayback.Detached), state.stagedSpec())
    }

    @Test
    fun secondHandleFailsWithoutRestaging() {
        val state = DetachedSessionState()
        admitDetachedPlayer(detached, state, CacheSizeClaims()::claim)

        assertFailsWith<LightAudioPlayerException> {
            admitDetachedPlayer(detached.copy(cache = null), state, CacheSizeClaims()::claim)
        }

        assertEquals(detached, state.stagedSpec())
    }
}
