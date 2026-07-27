package com.thelightphone.sdk.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightAudioPlayerTest {
    @Test
    fun queueNeighboursGateTheSkipControls() {
        // Middle of a three-item queue offers both directions.
        assertTrue(hasNextItem(index = 1, size = 3))
        assertTrue(hasPreviousItem(index = 1))
        // The ends offer only one.
        assertFalse(hasPreviousItem(index = 0))
        assertFalse(hasNextItem(index = 2, size = 3))
        // A single item offers neither, and an empty queue (index -1) is inert.
        assertFalse(hasNextItem(index = 0, size = 1))
        assertFalse(hasNextItem(index = -1, size = 0))
        assertFalse(hasPreviousItem(index = -1))
    }

    @Test
    fun skipPositionClampsToStartAndDuration() {
        assertEquals(0L, skipPosition(positionMs = 5_000L, durationMs = 60_000L, deltaMs = -15_000L))
        assertEquals(20_000L, skipPosition(positionMs = 5_000L, durationMs = 60_000L, deltaMs = 15_000L))
        assertEquals(60_000L, skipPosition(positionMs = 55_000L, durationMs = 60_000L, deltaMs = 15_000L))
        assertEquals(0L, skipPosition(positionMs = 5_000L, durationMs = 0L, deltaMs = 15_000L))
    }

    @Test
    fun sourceUriMapsAssetsAndUrls() {
        assertEquals(
            "asset:///audio/sample.ogg",
            LightAudioSource.AssetSource("/audio/sample.ogg").uriString(),
        )
        assertEquals(
            "https://example.com/live.mp3",
            LightAudioSource.UrlSource("https://example.com/live.mp3").uriString(),
        )
    }
}
