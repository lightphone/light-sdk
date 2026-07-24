package com.thelightphone.audiodemo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

class SpectrumAnalyzer(
    private val fftSize: Int = 1_024,
    private val bandCount: Int = 32,
) {
    init {
        require(fftSize > 0 && fftSize.countOneBits() == 1)
        require(bandCount > 0)
    }

    private val real = DoubleArray(fftSize)
    private val imaginary = DoubleArray(fftSize)
    private val window = DoubleArray(fftSize) { index ->
        0.5 * (1.0 - cos(2.0 * PI * index / (fftSize - 1)))
    }
    private var peakMagnitude = 0f

    fun reset() {
        peakMagnitude = 0f
    }

    fun analyze(samples: ShortArray, sampleRate: Int): FloatArray {
        for (index in 0 until fftSize) {
            real[index] = (samples.getOrElse(index) { 0 } / Short.MAX_VALUE.toDouble()) * window[index]
            imaginary[index] = 0.0
        }
        fft(real, imaginary)

        val bands = FloatArray(bandCount)
        val nyquist = sampleRate / 2.0
        val minFrequency = MIN_FREQUENCY.coerceAtMost(nyquist)
        val maxFrequency = MAX_FREQUENCY.coerceAtMost(nyquist)
        for (band in bands.indices) {
            val low = exponentialFrequency(minFrequency, maxFrequency, band.toDouble() / bandCount)
            val high = exponentialFrequency(minFrequency, maxFrequency, (band + 1.0) / bandCount)
            val lowBin = (low * fftSize / sampleRate).toInt().coerceIn(1, fftSize / 2)
            val highBin = (high * fftSize / sampleRate).toInt().coerceIn(lowBin + 1, fftSize / 2 + 1)
            var magnitude = 0.0
            for (bin in lowBin until highBin) {
                magnitude = maxOf(magnitude, real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
            }
            bands[band] = ln(1.0 + magnitude * MAGNITUDE_GAIN).toFloat()
            peakMagnitude = maxOf(peakMagnitude, bands[band])
        }
        if (peakMagnitude > 0f) {
            for (index in bands.indices) {
                bands[index] = (bands[index] / peakMagnitude).coerceIn(0f, 1f)
            }
        }
        return bands
    }
}

private fun fft(real: DoubleArray, imaginary: DoubleArray) {
    val size = real.size
    var target = 0
    for (index in 1 until size) {
        var bit = size shr 1
        while (target and bit != 0) {
            target = target xor bit
            bit = bit shr 1
        }
        target = target xor bit
        if (index < target) {
            val realValue = real[index]
            real[index] = real[target]
            real[target] = realValue
            val imaginaryValue = imaginary[index]
            imaginary[index] = imaginary[target]
            imaginary[target] = imaginaryValue
        }
    }

    var length = 2
    while (length <= size) {
        val angle = -2.0 * PI / length
        val stepReal = cos(angle)
        val stepImaginary = sin(angle)
        for (start in 0 until size step length) {
            var twiddleReal = 1.0
            var twiddleImaginary = 0.0
            for (offset in 0 until length / 2) {
                val even = start + offset
                val odd = even + length / 2
                val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                real[odd] = real[even] - oddReal
                imaginary[odd] = imaginary[even] - oddImaginary
                real[even] += oddReal
                imaginary[even] += oddImaginary
                val nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                twiddleReal = nextReal
            }
        }
        length = length shl 1
    }
}

private fun exponentialFrequency(min: Double, max: Double, fraction: Double): Double =
    min * (max / min).pow(fraction)

private fun Double.pow(exponent: Double): Double = kotlin.math.exp(ln(this) * exponent)

private const val MIN_FREQUENCY = 80.0
private const val MAX_FREQUENCY = 8_000.0
private const val MAGNITUDE_GAIN = 10_000.0
