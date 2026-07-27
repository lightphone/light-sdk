package com.thelightphone.audiodemo

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WavPcmDecoderTest {
    @Test
    fun decodesMono16BitPcmWithNonAudioChunk() {
        val wav = wav(
            channels = 1,
            bitsPerSample = 16,
            sampleRate = 48_000,
            samples = intArrayOf(Short.MIN_VALUE.toInt(), 0, Short.MAX_VALUE.toInt()),
            extraChunk = byteArrayOf(1, 2, 3),
        )

        val decoded = decodePcmWav(wav)

        assertEquals(48_000, decoded.sampleRate)
        assertContentEquals(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE), decoded.samples)
    }

    @Test
    fun convertsUnsigned8BitStereoToMono16Bit() {
        val wav = wav(
            channels = 2,
            bitsPerSample = 8,
            sampleRate = 11_025,
            samples = intArrayOf(0, 255, 128, 128),
        )

        val decoded = decodePcmWav(wav)

        assertEquals(11_025, decoded.sampleRate)
        assertContentEquals(shortArrayOf(-128, 0), decoded.samples)
    }

    @Test
    fun linearlyResamplesMonoPcm() {
        val resampled = resampleMonoPcm(shortArrayOf(0, 1_000, 2_000), 2, 4)

        assertContentEquals(shortArrayOf(0, 500, 1_000, 1_500, 2_000, 2_000), resampled)
    }

    @Test
    fun rejectsCompressedWav() {
        val wav = wav(
            channels = 1,
            bitsPerSample = 16,
            sampleRate = 48_000,
            samples = intArrayOf(0),
            audioFormat = 3,
        )

        assertFailsWith<IllegalArgumentException> { decodePcmWav(wav) }
    }

    private fun wav(
        channels: Int,
        bitsPerSample: Int,
        sampleRate: Int,
        samples: IntArray,
        extraChunk: ByteArray? = null,
        audioFormat: Int = 1,
    ): ByteArray {
        val bytesPerSample = bitsPerSample / 8
        val data = ByteArray(samples.size * bytesPerSample)
        samples.forEachIndexed { index, sample ->
            if (bitsPerSample == 8) {
                data[index] = sample.toByte()
            } else {
                data[index * 2] = sample.toByte()
                data[index * 2 + 1] = (sample shr 8).toByte()
            }
        }
        val chunks = buildList {
            add(chunk("fmt ", ByteArray(16).also { format ->
                format.put16(0, audioFormat)
                format.put16(2, channels)
                format.put32(4, sampleRate)
                format.put32(8, sampleRate * channels * bytesPerSample)
                format.put16(12, channels * bytesPerSample)
                format.put16(14, bitsPerSample)
            }))
            if (extraChunk != null) add(chunk("JUNK", extraChunk))
            add(chunk("data", data))
        }
        val bodySize = chunks.sumOf(ByteArray::size)
        return ByteArray(12 + bodySize).also { output ->
            output.putAscii(0, "RIFF")
            output.put32(4, bodySize + 4)
            output.putAscii(8, "WAVE")
            var offset = 12
            chunks.forEach { bytes ->
                bytes.copyInto(output, offset)
                offset += bytes.size
            }
        }
    }

    private fun chunk(id: String, content: ByteArray): ByteArray {
        val paddedSize = content.size + (content.size and 1)
        return ByteArray(8 + paddedSize).also { output ->
            output.putAscii(0, id)
            output.put32(4, content.size)
            content.copyInto(output, 8)
        }
    }

    private fun ByteArray.putAscii(offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(this, offset)
    }

    private fun ByteArray.put16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }

    private fun ByteArray.put32(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
        this[offset + 2] = (value shr 16).toByte()
        this[offset + 3] = (value shr 24).toByte()
    }
}
