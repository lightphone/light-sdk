package com.thelightphone.sdk.emulator

import android.content.Context
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class ModalType {
    RingerVol, CallVol, AlarmVol, MediaVol, Silent, VibrateOnly
}

data class AudioModal(val type: ModalType, val value: Float)

class LightAudioManager(private val context: Context) {

    companion object {
        private const val TAG = "LightAudioManager"
        private const val RING_STREAM = AudioManager.STREAM_RING
    }

    private val am get() = context.getSystemService(AudioManager::class.java)

    private val _ringerVolume = MutableStateFlow(currentNormalizedVolume())
    val ringerVolume: StateFlow<Float> = _ringerVolume.asStateFlow()

    // Ladder, low → high: silent, vibrate-only, audible 1..max
    fun stepUp(): AudioModal {
        val modal = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> setVibrateOnly()
            AudioManager.RINGER_MODE_VIBRATE -> setAudible(1)
            else -> {
                val current = am.getStreamVolume(RING_STREAM)
                val max = am.getStreamMaxVolume(RING_STREAM)
                setAudible((current + 1).coerceAtMost(max))
            }
        }
        _ringerVolume.value = currentNormalizedVolume()
        return modal
    }

    fun stepDown(): AudioModal {
        val modal = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> currentModal()
            AudioManager.RINGER_MODE_VIBRATE -> setSilent()
            else -> {
                val current = am.getStreamVolume(RING_STREAM)
                if (current <= 1) setVibrateOnly() else setAudible(current - 1)
            }
        }
        _ringerVolume.value = currentNormalizedVolume()
        return modal
    }

    fun setRingerVolume(normalized: Float) {
        val max = am.getStreamMaxVolume(RING_STREAM).coerceAtLeast(1)
        val volume = (normalized * max).roundToInt().coerceIn(1, max)
        runCatchingRinger { am.setStreamVolume(RING_STREAM, volume, 0) }
        _ringerVolume.value = currentNormalizedVolume()
    }

    private fun setAudible(volume: Int): AudioModal {
        runCatchingRinger { am.setStreamVolume(RING_STREAM, volume, 0) }
        val max = am.getStreamMaxVolume(RING_STREAM).coerceAtLeast(1)
        return AudioModal(ModalType.RingerVol, volume.toFloat() / max)
    }

    private fun setVibrateOnly(): AudioModal {
        runCatchingRinger { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
        vibrateTick()
        return AudioModal(ModalType.VibrateOnly, 0f)
    }

    private fun setSilent(): AudioModal {
        runCatchingRinger { am.ringerMode = AudioManager.RINGER_MODE_SILENT }
        return AudioModal(ModalType.Silent, 0f)
    }

    private fun currentModal(): AudioModal = when (am.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> AudioModal(ModalType.Silent, 0f)
        AudioManager.RINGER_MODE_VIBRATE -> AudioModal(ModalType.VibrateOnly, 0f)
        else -> AudioModal(ModalType.RingerVol, currentNormalizedVolume())
    }

    private fun currentNormalizedVolume(): Float {
        val max = am.getStreamMaxVolume(RING_STREAM).coerceAtLeast(1)
        return am.getStreamVolume(RING_STREAM).toFloat() / max
    }

    private fun vibrateTick() {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
            .vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private inline fun runCatchingRinger(block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            Log.w(TAG, "Ringer change denied — grant notification policy access to this app", e)
        }
    }
}
