package com.thelightphone.audiodemo

import kotlin.math.roundToInt

internal data class DecodedWav(
    val sampleRate: Int,
    val samples: ShortArray,
)

internal fun decodePcmWav(bytes: ByteArray): DecodedWav {
    require(bytes.size >= RIFF_HEADER_SIZE && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WAVE") {
        "Not a RIFF/WAVE file"
    }

    var format: WavFormat? = null
    var dataOffset = -1
    var dataSize = 0
    var offset = RIFF_HEADER_SIZE
    while (offset + CHUNK_HEADER_SIZE <= bytes.size) {
        val chunkId = bytes.ascii(offset, 4)
        val chunkSize = bytes.uint32Le(offset + 4)
        require(chunkSize <= Int.MAX_VALUE) { "WAV chunk is too large" }
        val contentOffset = offset + CHUNK_HEADER_SIZE
        val contentEnd = contentOffset.toLong() + chunkSize
        require(contentEnd <= bytes.size) { "Truncated WAV chunk: $chunkId" }

        when (chunkId) {
            "fmt " -> format = parseFormat(bytes, contentOffset, chunkSize.toInt())
            "data" -> if (dataOffset < 0) {
                dataOffset = contentOffset
                dataSize = chunkSize.toInt()
            }
        }
        offset = (contentEnd + (chunkSize and 1L)).toInt()
    }

    val wavFormat = requireNotNull(format) { "WAV has no fmt chunk" }
    require(dataOffset >= 0) { "WAV has no data chunk" }
    val bytesPerSample = wavFormat.bitsPerSample / Byte.SIZE_BITS
    val frameSize = wavFormat.channels * bytesPerSample
    require(wavFormat.blockAlign == frameSize) { "Unsupported WAV block alignment" }

    val frameCount = dataSize / frameSize
    val samples = ShortArray(frameCount)
    for (frame in 0 until frameCount) {
        val frameOffset = dataOffset + frame * frameSize
        var sum = 0
        for (channel in 0 until wavFormat.channels) {
            val sampleOffset = frameOffset + channel * bytesPerSample
            sum += when (wavFormat.bitsPerSample) {
                8 -> ((bytes[sampleOffset].toInt() and 0xff) - 128) shl 8
                16 -> bytes.int16Le(sampleOffset)
                else -> error("validated bits per sample")
            }
        }
        samples[frame] = (sum / wavFormat.channels).toShort()
    }
    return DecodedWav(wavFormat.sampleRate, samples)
}

internal fun resampleMonoPcm(samples: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
    require(sourceRate > 0 && targetRate > 0) { "Sample rates must be positive" }
    if (samples.isEmpty() || sourceRate == targetRate) return samples.copyOf()

    val targetSize = ((samples.size.toLong() * targetRate + sourceRate / 2) / sourceRate)
        .coerceAtLeast(1L)
        .toInt()
    return ShortArray(targetSize) { targetIndex ->
        val sourcePosition = targetIndex.toDouble() * sourceRate / targetRate
        val leftIndex = sourcePosition.toInt().coerceAtMost(samples.lastIndex)
        val rightIndex = (leftIndex + 1).coerceAtMost(samples.lastIndex)
        val fraction = sourcePosition - leftIndex
        (samples[leftIndex] + (samples[rightIndex] - samples[leftIndex]) * fraction)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

private fun parseFormat(bytes: ByteArray, offset: Int, size: Int): WavFormat {
    require(size >= PCM_FORMAT_SIZE) { "WAV fmt chunk is too short" }
    val audioFormat = bytes.uint16Le(offset)
    val channels = bytes.uint16Le(offset + 2)
    val sampleRate = bytes.uint32Le(offset + 4)
    val blockAlign = bytes.uint16Le(offset + 12)
    val bitsPerSample = bytes.uint16Le(offset + 14)
    require(audioFormat == PCM_FORMAT) { "Only PCM WAV is supported" }
    require(channels in 1..2) { "Only mono and stereo WAV is supported" }
    require(sampleRate in 1..Int.MAX_VALUE.toLong()) { "Invalid WAV sample rate" }
    require(bitsPerSample == 8 || bitsPerSample == 16) { "Only 8-bit and 16-bit WAV is supported" }
    return WavFormat(channels, sampleRate.toInt(), blockAlign, bitsPerSample)
}

private data class WavFormat(
    val channels: Int,
    val sampleRate: Int,
    val blockAlign: Int,
    val bitsPerSample: Int,
)

private fun ByteArray.ascii(offset: Int, length: Int): String =
    String(this, offset, length, Charsets.US_ASCII)

private fun ByteArray.uint16Le(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.int16Le(offset: Int): Int = uint16Le(offset).toShort().toInt()

private fun ByteArray.uint32Le(offset: Int): Long =
    (this[offset].toLong() and 0xff) or
        ((this[offset + 1].toLong() and 0xff) shl 8) or
        ((this[offset + 2].toLong() and 0xff) shl 16) or
        ((this[offset + 3].toLong() and 0xff) shl 24)

private const val RIFF_HEADER_SIZE = 12
private const val CHUNK_HEADER_SIZE = 8
private const val PCM_FORMAT_SIZE = 16
private const val PCM_FORMAT = 1
