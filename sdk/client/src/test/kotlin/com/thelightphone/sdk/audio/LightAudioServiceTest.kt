package com.thelightphone.sdk.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LightAudioServiceTest {
    @Test
    fun idleStopsOnlyWhenPausedAndUnheld() {
        assertTrue(shouldStartIdleStop(isPlaying = false, handleOpen = false))
        assertFalse(shouldStartIdleStop(isPlaying = false, handleOpen = true))
        assertFalse(shouldStartIdleStop(isPlaying = true, handleOpen = false))
        assertFalse(shouldStartIdleStop(isPlaying = true, handleOpen = true))
    }
}

class DetachedSessionStateTest {
    @Test
    fun usageCompatibilityAcceptsFreshAndSameUsageOnly() {
        assertTrue(isDetachedUsageCompatible(activeUsage = null, LightAudioUsage.Alarm))
        assertTrue(isDetachedUsageCompatible(LightAudioUsage.Music, LightAudioUsage.Music))
        assertFalse(isDetachedUsageCompatible(LightAudioUsage.Music, LightAudioUsage.Alarm))
    }

    @Test
    fun allowsOneHandleUntilClosed() {
        val state = DetachedSessionState()

        assertTrue(state.openHandle())
        assertFalse(state.openHandle())
        state.closeHandle()
        assertTrue(state.openHandle())
    }

    @Test
    fun reportsWhetherAHandleIsOpen() {
        val state = DetachedSessionState()

        assertFalse(state.isHandleOpen())
        state.openHandle()
        assertTrue(state.isHandleOpen())
        state.closeHandle()
        assertFalse(state.isHandleOpen())
    }

    @Test
    fun handleChangesNotifyTheServiceLivenessObserver() {
        val state = DetachedSessionState()
        var changes = 0
        state.setHandleChangedListener { changes++ }

        state.openHandle()
        state.closeHandle()

        assertEquals(2, changes)
    }

    /**
     * Releasing a handle does not stop detached playback,
     * so the live session's usage has to outlive it.
     */
    @Test
    fun sessionUsageOutlivesTheHandle() {
        val state = DetachedSessionState()

        state.openHandle()
        state.adoptUsage(LightAudioUsage.Alarm)
        state.closeHandle()

        assertFalse(state.isHandleOpen())
        assertEquals(LightAudioUsage.Alarm, state.activeUsage())
    }

    @Test
    fun clearingTheSessionLetsTheNextHandleStartFresh() {
        val state = DetachedSessionState()

        state.adoptUsage(LightAudioUsage.Alarm)
        state.clearSession()

        assertNull(state.activeUsage())
    }
}
