package com.thelightphone.sdk

import com.thelightphone.sdk.nfc.LightNfcAvailability
import com.thelightphone.sdk.ui.LightNfcTapState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LightNfcTapStateTest {
    @Test
    fun readyAvailabilityWaitsForATap() {
        assertEquals(LightNfcTapState.Waiting, LightNfcAvailability.Ready.toTapState())
    }

    @Test
    fun disabledAvailabilityGetsItsOwnState() {
        assertEquals(LightNfcTapState.Disabled, LightNfcAvailability.Disabled.toTapState())
    }

    @Test
    fun missingHardwareIsUnavailable() {
        assertEquals(LightNfcTapState.Unavailable, LightNfcAvailability.Unsupported.toTapState())
    }

    @Test
    fun missingPermissionGetsItsOwnState() {
        assertEquals(LightNfcTapState.PermissionMissing, LightNfcAvailability.PermissionMissing.toTapState())
    }

    @Test
    fun everyAvailabilityMapsToATapState() {
        val states = LightNfcAvailability.entries.map { it.toTapState() }

        assertEquals(LightNfcTapState.entries.toSet() - LightNfcTapState.Unknown, states.toSet())
    }

    @Test
    fun noAvailabilityEverReportsUnknown() {
        val states = LightNfcAvailability.entries.map { it.toTapState() }

        assertFalse(LightNfcTapState.Unknown in states)
    }
}
