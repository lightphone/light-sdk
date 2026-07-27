package com.thelightphone.audiodemo

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerScreenTest {
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
