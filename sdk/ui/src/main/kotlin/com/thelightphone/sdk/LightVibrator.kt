package com.thelightphone.sdk

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@JvmInline
value class VibrationAmplitude(val value: Int) {
    init {
        require(value in 0..255) { "Amplitude must be in 0..255" }
    }
}

fun vibrationAmplitudesOf(vararg values: Int): List<VibrationAmplitude> =
    values.map(::VibrationAmplitude)

enum class LightVibrationUsage {
    /** Feedback for a direct touch. */
    Touch,
    /** An alarm or timer the user set. Vibrates while the screen is off. */
    Alarm,
}

/**
 * Segment `i` lasts `durations[i]` at intensity `amplitudes[i]`,
 * where `0` is off and `255` is the motor's maximum.
 */
class LightVibrationWaveform(
    val durations: List<Duration>,
    val amplitudes: List<VibrationAmplitude>,
) {
    init {
        require(durations.size == amplitudes.size) { "Each duration needs one amplitude" }
        require(durations.all { it >= Duration.ZERO }) { "Durations must not be negative" }
    }

    val duration: Duration get() = durations.fold(Duration.ZERO, Duration::plus)
}

interface LightVibrator {
    fun click()
    fun vibrate(duration: Duration)
    fun vibrate(duration: Duration, amplitude: VibrationAmplitude)
    fun vibrate(waveform: LightVibrationWaveform, usage: LightVibrationUsage)
    fun cancel()
}

class ContextLightVibrator(context: Context) : LightVibrator {
    private val vibrator: Vibrator? = context.applicationContext
        .getSystemService(VibratorManager::class.java)
        ?.defaultVibrator
        ?.takeIf { it.hasVibrator() }

    override fun click() = vibrate(45.milliseconds)

    override fun vibrate(duration: Duration) {
        val vibrator = vibrator ?: return
        val durationMs = duration.inWholeMilliseconds
        if (durationMs <= 0L) return
        vibrator.vibrate(
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
        )
    }

    override fun vibrate(duration: Duration, amplitude: VibrationAmplitude) {
        val vibrator = vibrator ?: return
        val durationMs = duration.inWholeMilliseconds
        if (durationMs <= 0L || amplitude.value == 0) return
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(durationMs, amplitude.value)
        } else {
            VibrationEffect.createWaveform(
                LightVibrationWaveform(listOf(duration), listOf(amplitude)).pulseWidthTimings(),
                NO_REPEAT,
            )
        }
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
    }

    override fun vibrate(waveform: LightVibrationWaveform, usage: LightVibrationUsage) {
        val vibrator = vibrator ?: return
        if (waveform.duration <= Duration.ZERO) return
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(
                waveform.durations.map(Duration::inWholeMilliseconds).toLongArray(),
                waveform.amplitudes.map(VibrationAmplitude::value).toIntArray(),
                NO_REPEAT,
            )
        } else {
            VibrationEffect.createWaveform(waveform.pulseWidthTimings(), NO_REPEAT)
        }
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(usage.toPlatformUsage()))
    }

    override fun cancel() {
        vibrator?.cancel()
    }

    private companion object {
        const val NO_REPEAT = -1
    }
}

private fun LightVibrationUsage.toPlatformUsage(): Int = when (this) {
    LightVibrationUsage.Touch -> VibrationAttributes.USAGE_TOUCH
    LightVibrationUsage.Alarm -> VibrationAttributes.USAGE_ALARM
}

/**
 * On/off timings for [VibrationEffect.createWaveform] without amplitudes:
 * alternating off/on durations, starting with off. Each segment is on for
 * the fraction of its duration given by its amplitude, then off.
 */
internal fun LightVibrationWaveform.pulseWidthTimings(): LongArray {
    val timings = mutableListOf(0L)
    durations.forEachIndexed { index, duration ->
        val durationMs = duration.inWholeMilliseconds
        val onMs = durationMs * amplitudes[index].value / 255
        timings += onMs
        timings += durationMs - onMs
    }
    return timings.toLongArray()
}
