package com.thelightphone.sdk.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class LightAudioPlayerTest {
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
