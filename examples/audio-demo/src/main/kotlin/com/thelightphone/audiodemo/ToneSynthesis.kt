package com.thelightphone.audiodemo

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Mono, 16-bit sine with a short attack/release envelope to avoid clicks. */
fun synthesizeSine(sampleRate: Int, freqHz: Double, durationMs: Int): ShortArray {
    require(freqHz > 0.0 && freqHz < sampleRate / 2.0) { "Frequency must be below Nyquist" }
    require(durationMs > 0) { "Duration must be positive" }

    val frames = (sampleRate.toLong() * durationMs / 1_000L).toInt()
    val envelopeFrames = min(frames / 2, sampleRate * ENVELOPE_MS / 1_000)
    return ShortArray(frames) { frame ->
        val attack = if (envelopeFrames == 0) 1.0 else min(1.0, frame.toDouble() / envelopeFrames)
        val release = if (envelopeFrames == 0) 1.0 else min(1.0, (frames - 1 - frame).toDouble() / envelopeFrames)
        val envelope = min(attack, release)
        (sin(2.0 * PI * freqHz * frame / sampleRate) * envelope * Short.MAX_VALUE).toInt().toShort()
    }
}

private const val ENVELOPE_MS = 5
