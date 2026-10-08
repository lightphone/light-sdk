package com.thelightphone.sdk.audio

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.metadata.icy.IcyInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamMetadataTest {
    @Test
    fun icyInfoMapsToStreamMetadata() {
        val metadata = Metadata(icy(title = "Artist - Title", url = "https://example.com"))

        assertEquals(
            LightStreamMetadata(title = "Artist - Title", url = "https://example.com"),
            metadata.toLightStreamMetadata(),
        )
    }

    @Test
    fun latestIcyEntryWins() {
        val metadata = Metadata(icy(title = "First"), icy(title = "Second"))

        assertEquals("Second", metadata.toLightStreamMetadata()?.title)
    }

    @Test
    fun metadataWithoutIcyTitleOrUrlIsIgnored() {
        assertNull(Metadata(icy(title = null)).toLightStreamMetadata())
        assertNull(
            Metadata(IcyHeaders(128_000, "Genre", "Name", "https://example.com", true, 16_000))
                .toLightStreamMetadata(),
        )
    }

    private fun icy(title: String?, url: String? = null) = IcyInfo(ByteArray(0), title, url)
}
