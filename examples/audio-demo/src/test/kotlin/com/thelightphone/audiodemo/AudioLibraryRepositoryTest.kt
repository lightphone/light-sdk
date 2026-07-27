package com.thelightphone.audiodemo

import com.thelightphone.sdk.audio.LightAudioUsage
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AudioLibraryRepositoryTest {
    @Test
    fun sampleCatalogIncludesBundledMusicSpeechAndRemoteStreams() {
        val clips = SampleAudioCatalog.clips

        assertEquals(5, clips.size)
        assertEquals(3, clips.count { it.source is AudioClipSource.AssetSource })
        assertEquals(2, clips.count { it.source is AudioClipSource.UrlSource })
        assertEquals(2, clips.count { it.kind == AudioContentKind.Speech })
        assertTrue(clips.all { it.usage == LightAudioUsage.Music })
        assertTrue(clips.any { it.formatLabel == "OGG" })
        assertTrue(clips.any { it.formatLabel == "STREAM" && it.durationMs == 0L })
    }

    @Test
    fun playbackSelectionQueuesWholeLibraryAtSelectedClip() {
        val clips = SampleAudioCatalog.clips
        val selected = clips[3]

        val selection = playbackSelectionFor(selected, clips)

        assertEquals(clips, selection.queue)
        assertEquals(3, selection.startIndex)
    }

    @Test
    fun playbackSelectionRejectsClipOutsideLibrary() {
        val missing = SampleAudioCatalog.clips.first().copy(displayName = "Missing")

        assertFailsWith<IllegalArgumentException> {
            playbackSelectionFor(missing, SampleAudioCatalog.clips)
        }
    }

    @Test
    fun playNextControlsPauseAtEndWithoutChangingQueue() {
        assertEquals(false, pauseAtEndOfMediaItemsFor(playNext = true))
        assertEquals(true, pauseAtEndOfMediaItemsFor(playNext = false))
    }

    @Test
    fun listCombinesBundledClipsAndNewestRecordingsFirst() {
        val filesDir = createTempDirectory("audio-library").toFile()
        val recordings = File(filesDir, "recordings").apply { mkdirs() }
        File(recordings, "20260101-120000.m4a").writeBytes(byteArrayOf(1))
        File(recordings, "20260102-120000.m4a").writeBytes(byteArrayOf(2))
        val bundled = AudioClip(
            source = AudioClipSource.AssetSource("audio/sample.mp3"),
            displayName = "Sample",
            usage = LightAudioUsage.Music,
            kind = AudioContentKind.Music,
            durationMs = 1_000L,
            formatLabel = "MP3",
        )

        val clips = AudioLibraryRepository(
            filesDir = filesDir,
            bundledClips = listOf(bundled),
            durationResolver = { 2_000L },
        ).list()

        assertEquals(bundled, clips.first())
        val recordingFiles = clips.drop(1).map { (it.source as AudioClipSource.FileSource).file.name }
        assertEquals(listOf("20260102-120000.m4a", "20260101-120000.m4a"), recordingFiles)
        assertTrue(clips.drop(1).all { it.kind == AudioContentKind.Speech && it.durationMs == 2_000L })
    }

    @Test
    fun newRecordingFileUsesTimestampAndAvoidsCollision() {
        val filesDir = createTempDirectory("audio-library").toFile()
        val repository = AudioLibraryRepository(
            filesDir = filesDir,
            now = { Instant.parse("2026-01-02T12:00:00Z") },
        )

        val first = repository.newRecordingFile()
        first.createNewFile()
        val second = repository.newRecordingFile()

        assertTrue(first.name.endsWith(".m4a"))
        assertNotEquals(first.name, second.name)
    }
}
