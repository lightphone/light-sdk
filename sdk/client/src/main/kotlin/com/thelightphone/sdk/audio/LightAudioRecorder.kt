package com.thelightphone.sdk.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File

/**
 * Records microphone input to an MPEG-4 file with AAC audio.
 *
 * Call [release] when the owning screen is destroyed.
 */
class LightAudioRecorder internal constructor(
    private val context: Context,
    private val config: RecorderConfig
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs = 0L

    /**
     * Starts a new recording at [file], cancelling any active recording first.
     * Parent directories are created as needed and partial output is deleted on
     * failure. The tool must hold `android.permission.RECORD_AUDIO`.
     *
     * @throws LightAudioRecorderException when the platform cannot prepare or
     *   start recording
     */
    fun start(file: File) {
        cancel()
        file.parentFile?.mkdirs()
        outputFile = file
        startedAtMs = SystemClock.elapsedRealtime()
        val newRecorder = MediaRecorder(context)
        try {
            newRecorder.apply {
                setAudioSource(config.source.toMediaRecorderSource())
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                if (config.sampleRate > 0) {
                    setAudioSamplingRate(config.sampleRate)
                }
                if (config.channels > 0) {
                    setAudioChannels(config.channels)
                }
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = newRecorder
        } catch (e: SecurityException) {
            runCatching { newRecorder.release() }
            outputFile?.delete()
            outputFile = null
            startedAtMs = 0L
            throw LightAudioRecorderException("Microphone permission was denied", e)
        } catch (e: Exception) {
            runCatching { newRecorder.release() }
            outputFile?.delete()
            outputFile = null
            startedAtMs = 0L
            throw LightAudioRecorderException("Failed to start recording", e)
        }
    }

    /**
     * Stops and finalizes the active recording.
     *
     * @return elapsed recording milliseconds, or `0` when inactive or when the
     *   platform rejects the recording. Invalid output is deleted.
     */
    fun stop(): Long {
        val activeRecorder = recorder ?: return 0L
        recorder = null
        val durationMs = SystemClock.elapsedRealtime() - startedAtMs
        return try {
            activeRecorder.stop()
            durationMs
        } catch (_: RuntimeException) {
            outputFile?.delete()
            0L
        } finally {
            runCatching { activeRecorder.release() }
            outputFile = null
            startedAtMs = 0L
        }
    }

    /** Cancels the active recording and deletes its output. Idempotent. */
    fun cancel() {
        val activeRecorder = recorder ?: return
        recorder = null
        runCatching { activeRecorder.stop() }
        runCatching { activeRecorder.release() }
        outputFile?.delete()
        outputFile = null
        startedAtMs = 0L
    }

    /** Releases recorder resources by cancelling any active recording. */
    fun release() {
        cancel()
    }
}
