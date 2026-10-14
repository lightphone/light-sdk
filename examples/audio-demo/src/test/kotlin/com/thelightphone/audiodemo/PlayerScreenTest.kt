package com.thelightphone.audiodemo

import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import com.thelightphone.sdk.audio.LightQueueItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class PlayerScreenTest {
    @Test
    fun skipPositionClampsToStartAndDuration() {
        assertEquals(0L, skipPosition(positionMs = 5_000L, durationMs = 60_000L, deltaMs = -15_000L))
        assertEquals(20_000L, skipPosition(positionMs = 5_000L, durationMs = 60_000L, deltaMs = 15_000L))
        assertEquals(60_000L, skipPosition(positionMs = 55_000L, durationMs = 60_000L, deltaMs = 15_000L))
        assertEquals(0L, skipPosition(positionMs = 5_000L, durationMs = 0L, deltaMs = 15_000L))
    }

    @Test
    fun currentItemFollowsReorderedQueue() {
        val first = LightQueueItem("first", LightMediaMetadata("First"))
        val second = LightQueueItem("second", LightMediaMetadata("Second"))

        assertEquals(second, queueItemAt(listOf(second, first), 0))
        assertEquals(null, queueItemAt(emptyList(), 0))
    }

    @Test
    fun repairTargetsTheFailedExpiredItem() {
        val id = freshExpiredDawnId()
        val expired = SampleAudioCatalog.clips.first { it.id == SampleAudioCatalog.EXPIRED_DAWN_ID }
            .copy(id = id)
        val error = LightAudioError(LightAudioErrorKind.Source, "ERROR_CODE_IO_UNSPECIFIED", 1, expired.id)

        assertEquals(true, isExpiredDawnId(id))
        assertEquals(1, repairIndex(error, id))
        val repaired = expired.repairedDawnItem()
        assertEquals(expired.id, repaired.id)
        assertEquals(LightAudioSource.UrlSource(SampleAudioCatalog.DAWN_URL), repaired.source)
        assertEquals(null, repairIndex(error, freshExpiredDawnId()))
        assertEquals(null, repairIndex(error.copy(itemId = "other"), id))
        assertEquals(null, repairIndex(error.copy(itemIndex = NO_MEDIA_ITEM), id))
        assertEquals(null, repairIndex(error.copy(kind = LightAudioErrorKind.Unsupported), id))
    }

    @Test
    fun screenEntryRotatesOnlyTheBrokenDawnIdAndFindsItInAReorderedQueue() {
        val firstId = freshExpiredDawnId()
        val secondId = freshExpiredDawnId()
        val clips = SampleAudioCatalog.clips.withExpiredDawnId(firstId)
        val refreshed = clips.withExpiredDawnId(secondId)
        val expired = refreshed.single { it.id == secondId }

        assertEquals(firstId, clips.single { isExpiredDawnId(it.id) }.id)
        assertEquals(secondId, expired.id)
        assertEquals(clips.filterNot { isExpiredDawnId(it.id) }, refreshed.filterNot { isExpiredDawnId(it.id) })
        assertEquals(
            1,
            listOf(
                LightQueueItem("other", LightMediaMetadata("Other")),
                LightQueueItem(firstId, LightMediaMetadata("Broken Dawn")),
            ).staleExpiredDawnIndex(secondId),
        )
    }

    @Test
    fun playbackModeAlternatesBetweenAttachedAndDetached() {
        assertEquals(LightAudioPlayback.Detached, nextPlaybackMode(LightAudioPlayback.Attached))
        assertEquals(LightAudioPlayback.Attached, nextPlaybackMode(LightAudioPlayback.Detached))
    }

    @Test
    fun detachedModePersistsAcrossViewModelInstances() = runBlocking {
        val store = InMemoryPlayerModeStore()
        PlayerDemoMode(store).set(LightAudioPlayback.Detached)

        assertEquals(LightAudioPlayback.Detached, PlayerDemoMode(store).load())

        PlayerDemoMode(store).set(LightAudioPlayback.Attached)
        assertEquals(LightAudioPlayback.Attached, PlayerDemoMode(store).load())
    }

    @Test
    fun playbackErrorMessageExplainsHowToContinue() {
        assertEquals(
            "Source: ERROR_CODE_IO_FILE_NOT_FOUND. Select another item to continue.",
            playbackErrorMessage(
                LightAudioError(
                    LightAudioErrorKind.Source,
                    "ERROR_CODE_IO_FILE_NOT_FOUND",
                    itemIndex = 2,
                ),
            ),
        )
    }

    @Test
    fun unknownDurationShowsDashAndDisablesSeeking() {
        assertEquals(
            PlayerDurationDisplay(totalTime = "--:--", canSeek = false),
            playerDurationDisplay(0L),
        )
        assertEquals(
            PlayerDurationDisplay(totalTime = "--:--", canSeek = false),
            playerDurationDisplay(-1L),
        )
    }

    @Test
    fun knownDurationShowsFormattedTotalAndEnablesSeeking() {
        assertEquals(
            PlayerDurationDisplay(totalTime = "01:05", canSeek = true),
            playerDurationDisplay(65_999L),
        )
    }
}

private class InMemoryPlayerModeStore : PlayerModeStore {
    private var playback = LightAudioPlayback.Attached

    override suspend fun getPlayback(): LightAudioPlayback = playback

    override suspend fun setPlayback(playback: LightAudioPlayback) {
        this.playback = playback
    }
}
