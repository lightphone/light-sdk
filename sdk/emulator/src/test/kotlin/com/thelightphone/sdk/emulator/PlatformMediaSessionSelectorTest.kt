package com.thelightphone.sdk.emulator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformMediaSessionSelectorTest {
    @Test
    fun playingBeatsPaused() {
        assertEquals("playing", select("paused", paused("paused"), playing("playing")))
    }

    @Test
    fun androidOrderSelectsFirstPlayingSession() {
        assertEquals("first", select(null, playing("first"), playing("second")))
    }

    @Test
    fun currentPlayingSessionRemainsStable() {
        assertEquals("current", select("current", playing("other"), playing("current")))
    }

    @Test
    fun currentPausedSessionRemainsStableWhenNothingPlays() {
        assertEquals("current", select("current", paused("other"), paused("current")))
    }

    @Test
    fun removalSelectsNextEligibleSession() {
        assertEquals("next", select("removed", paused("next")))
    }

    @Test
    fun nonDisplayableCurrentSessionFallsBackToDisplayableSession() {
        assertEquals("fallback", select("stopped", nonDisplayable("stopped"), paused("fallback")))
    }

    @Test
    fun nonDisplayableSessionsAreSkippedInAndroidOrder() {
        assertEquals("displayable", select(null, nonDisplayable("stopped"), paused("displayable")))
    }

    @Test
    fun onlyNonDisplayableSessionsClearsSelection() {
        assertNull(select("stopped", nonDisplayable("stopped")))
    }

    @Test
    fun emptyInputClearsSelection() {
        assertNull(select("removed"))
    }

    private fun select(currentId: String?, vararg sessions: SelectableMediaSession): String? =
        selectPlatformMediaSession(sessions.toList(), currentId)

    private fun playing(id: String) = SelectableMediaSession(id, playing = true, displayable = true)

    private fun paused(id: String) = SelectableMediaSession(id, playing = false, displayable = true)

    private fun nonDisplayable(id: String) =
        SelectableMediaSession(id, playing = false, displayable = false)
}
