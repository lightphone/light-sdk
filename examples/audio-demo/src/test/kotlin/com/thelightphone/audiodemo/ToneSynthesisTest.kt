package com.thelightphone.audiodemo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToneSynthesisTest {
    @Test
    fun sineHasExpectedSizeAndEnvelope() {
        val pcm = synthesizeSine(sampleRate = 48_000, freqHz = 440.0, durationMs = 200)

        assertEquals(48_000 * 200 / 1_000, pcm.size)
        assertEquals(0, pcm.first())
        assertEquals(0, pcm.last())
        assertTrue(pcm.any { it != 0.toShort() })
    }

    @Test
    fun sineRejectsInvalidInput() {
        assertFailsWith<IllegalArgumentException> {
            synthesizeSine(sampleRate = 48_000, freqHz = 24_000.0, durationMs = 200)
        }
        assertFailsWith<IllegalArgumentException> {
            synthesizeSine(sampleRate = 48_000, freqHz = 440.0, durationMs = 0)
        }
    }
}
