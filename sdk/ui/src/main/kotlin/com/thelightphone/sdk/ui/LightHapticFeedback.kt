package com.thelightphone.sdk.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object LightHapticFeedback {

    // currently optimized for LP3, which has a "slow" motor
    fun click(context: Context) = vibrateForDuration(context, 45.milliseconds)

    fun vibrateForDuration(context: Context, duration: Duration) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(duration.inWholeMilliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
