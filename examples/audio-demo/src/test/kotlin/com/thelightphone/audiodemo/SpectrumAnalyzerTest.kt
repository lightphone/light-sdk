package com.thelightphone.audiodemo

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class SpectrumAnalyzerTest {
    @Test
    fun higherTonePeaksInHigherBand() {
        val analyzer = SpectrumAnalyzer()

        val lowPeak = analyzer.analyze(tone(440.0), SAMPLE_RATE).dominantBand()
        val highPeak = analyzer.analyze(tone(2_000.0), SAMPLE_RATE).dominantBand()

        assertTrue(lowPeak >= 0)
        assertTrue(highPeak > lowPeak)
    }

    @Test
    fun silenceProducesEmptySpectrum() {
        val spectrum = SpectrumAnalyzer().analyze(ShortArray(1_024), SAMPLE_RATE)

        assertTrue(spectrum.all { it == 0f })
    }

    @Test
    fun spectrumUsesHighestMagnitudeSeenDuringSession() {
        val analyzer = SpectrumAnalyzer()

        val initialQuietPeak = analyzer.analyze(tone(1_500.0, amplitude = 0.01), SAMPLE_RATE).max()
        val loudPeak = analyzer.analyze(tone(1_500.0, amplitude = 0.8), SAMPLE_RATE).max()
        val laterQuietPeak = analyzer.analyze(tone(1_500.0, amplitude = 0.01), SAMPLE_RATE).max()

        assertTrue(initialQuietPeak > 0.99f, "initial quiet peak was $initialQuietPeak")
        assertTrue(loudPeak > 0.99f, "loud peak was $loudPeak")
        assertTrue(laterQuietPeak < loudPeak, "later quiet peak $laterQuietPeak did not fall below $loudPeak")
    }

    @Test
    fun resetStartsAnewRelativeSession() {
        val analyzer = SpectrumAnalyzer()
        analyzer.analyze(tone(1_500.0, amplitude = 0.8), SAMPLE_RATE)
        val quietBeforeReset = analyzer.analyze(tone(1_500.0, amplitude = 0.01), SAMPLE_RATE).max()

        analyzer.reset()
        val quietAfterReset = analyzer.analyze(tone(1_500.0, amplitude = 0.01), SAMPLE_RATE).max()

        assertTrue(quietBeforeReset < quietAfterReset)
        assertTrue(quietAfterReset > 0.99f)
    }

    @Test
    fun spectrumIsFiniteAndClamped() {
        val spectrum = SpectrumAnalyzer().analyze(tone(1_500.0), SAMPLE_RATE)

        assertTrue(spectrum.all { it.isFinite() && it in 0f..1f })
    }

    private fun tone(frequency: Double, amplitude: Double = 1.0): ShortArray = ShortArray(1_024) { index ->
        (sin(2.0 * PI * frequency * index / SAMPLE_RATE) * Short.MAX_VALUE * amplitude).toInt().toShort()
    }

    private fun FloatArray.dominantBand(): Int = indices.maxByOrNull { this[it] } ?: -1

    companion object {
        private const val SAMPLE_RATE = 48_000
    }
}
