package com.thelightphone.audiodemo

import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioPlayback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class PlayerScreenTest {
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
