package com.thelightphone.sdk.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LightAudioPlayerTest {
    @Test
    fun skipPositionClampsToStartAndDuration() {
        assertEquals(0L, skipPosition(positionMs = 5_000L, durationMs = 60_000L, deltaMs = -15_000L))
        assertEquals(20_000L, skipPosition(positionMs = 5_000L, durationMs = 60_000L, deltaMs = 15_000L))
        assertEquals(60_000L, skipPosition(positionMs = 55_000L, durationMs = 60_000L, deltaMs = 15_000L))
        assertEquals(0L, skipPosition(positionMs = 5_000L, durationMs = 0L, deltaMs = 15_000L))
    }

    @Test
    fun sourceUriMapsAssetsUrlsAndCustom() {
        assertEquals(
            "asset:///audio/sample.ogg",
            LightAudioSource.AssetSource("/audio/sample.ogg").uriString(),
        )
        assertEquals(
            "https://example.com/live.mp3",
            LightAudioSource.UrlSource("https://example.com/live.mp3").uriString(),
        )
        assertEquals(
            "demo:///track1",
            LightAudioSource.CustomSource("demo:///track1").uriString(),
        )
    }

    @Test
    fun mediaItemMappingForwardsMimeCacheKeyAndMediaId() {
        val item = LightAudioItem(
            source = LightAudioSource.CustomSource("https://cdn.example/ephemeral"),
            metadata = LightMediaMetadata(title = "Track"),
            mimeType = "application/dash+xml",
            customCacheKey = "stable:track:1",
            mediaId = "catalog:track:1",
        )
        assertEquals(
            MediaItemMapping(
                uri = "https://cdn.example/ephemeral",
                mediaId = "catalog:track:1",
                mimeType = "application/dash+xml",
                customCacheKey = "stable:track:1",
            ),
            item.mediaItemMapping(),
        )
    }

    @Test
    fun mediaItemMappingDefaultsMediaIdToUriWhenOmitted() {
        val item = LightAudioItem(
            source = LightAudioSource.UrlSource("https://example.com/a.mp3"),
            metadata = LightMediaMetadata(title = "A"),
        )
        val mapping = item.mediaItemMapping()
        assertEquals("https://example.com/a.mp3", mapping.uri)
        assertEquals("https://example.com/a.mp3", mapping.mediaId)
        assertNull(mapping.mimeType)
        assertNull(mapping.customCacheKey)
    }
}
