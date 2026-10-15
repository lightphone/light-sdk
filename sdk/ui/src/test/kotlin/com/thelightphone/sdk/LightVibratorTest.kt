package com.thelightphone.sdk

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class LightVibratorTest {
    @Test
    fun pulseWidthTimingsStartOffThenAlternateOnOff() {
        val waveform = LightVibrationWaveform(
            durations = listOf(100.milliseconds, 100.milliseconds, 100.milliseconds),
            amplitudes = vibrationAmplitudesOf(255, 51, 0),
        )

        assertContentEquals(
            longArrayOf(0L, 100L, 0L, 20L, 80L, 0L, 100L),
            waveform.pulseWidthTimings(),
        )
    }

    @Test
    fun pulseWidthTimingsKeepTotalDuration() {
        val waveform = LightVibrationWaveform(
            durations = listOf(50.milliseconds, 70.milliseconds, 30.milliseconds),
            amplitudes = vibrationAmplitudesOf(200, 99, 7),
        )

        assertEquals(waveform.duration.inWholeMilliseconds, waveform.pulseWidthTimings().sum())
    }

    @Test
    fun waveformRejectsMismatchedLists() {
        assertFailsWith<IllegalArgumentException> {
            LightVibrationWaveform(
                listOf(10.milliseconds, 10.milliseconds),
                vibrationAmplitudesOf(255),
            )
        }
    }

    @Test
    fun amplitudeRejectsOutOfRangeValue() {
        assertFailsWith<IllegalArgumentException> {
            VibrationAmplitude(256)
        }
        assertFailsWith<IllegalArgumentException> {
            VibrationAmplitude(-1)
        }
    }

    @Test
    fun waveformRejectsNegativeDuration() {
        assertFailsWith<IllegalArgumentException> {
            LightVibrationWaveform(listOf((-1).milliseconds), vibrationAmplitudesOf(255))
        }
    }
}
