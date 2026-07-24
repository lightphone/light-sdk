package com.thelightphone.sdk.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Captures microphone input as mono signed 16-bit PCM buffers.
 *
 * Collection owns capture lifetime: cancelling collection stops the microphone.
 * Use only one active collector per instance; create another capture for
 * concurrent consumers.
 */
class LightAudioCapture internal constructor(
    private val config: CaptureConfig
) {
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    // LightOS ensures RECORD_AUDIO is granted before this method is called.
    // Lint cannot detect that external check. Catch SecurityException in case
    // the permission is revoked before AudioRecord starts.
    @SuppressLint("MissingPermission")
    private fun start(onBuffer: (ShortArray, Int) -> Unit) {
        stop()
        val minBufferBytes = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            throw LightAudioCaptureException(
                "Unsupported capture format (sampleRate=${config.sampleRate})"
            )
        }

        val bufferFrames = captureBufferFrames(config, minBufferBytes)
        val bufferBytes = bufferFrames * Short.SIZE_BYTES
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(config.source.toMediaRecorderSource())
                .setAudioFormat(config.toAudioFormat())
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: SecurityException) {
            throw LightAudioCaptureException("Microphone permission was denied", e)
        } catch (e: Exception) {
            throw LightAudioCaptureException("Could not create the audio recorder", e)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw LightAudioCaptureException("Microphone is unavailable")
        }

        try {
            record.startRecording()
        } catch (e: SecurityException) {
            record.release()
            throw LightAudioCaptureException("Microphone permission was denied", e)
        } catch (e: Exception) {
            record.release()
            throw LightAudioCaptureException("Could not start the microphone", e)
        }

        audioRecord = record
        running.set(true)
        captureThread = thread(
            start = true,
            isDaemon = true,
            name = "LightAudioCapture"
        ) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(bufferFrames)
            while (running.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    onBuffer(buffer, read)
                }
            }
        }
    }

    private fun stop() {
        running.set(false)
        val record = audioRecord
        audioRecord = null
        if (record != null) {
            runCatching { record.stop() }
            record.release()
        }
        val thread = captureThread
        captureThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
        }
    }

    /**
     * Returns a conflated cold flow of PCM buffers at the configured sample
     * rate. Each collection starts microphone capture and cancellation stops it.
     * Slow collectors receive the newest available buffer. The tool must hold
     * `android.permission.RECORD_AUDIO` while collecting.
     *
     * @throws LightAudioCaptureException from collection when the format is
     *   unsupported or the microphone cannot be created or started
     */
    fun asFlow(): Flow<ShortArray> = callbackFlow {
        start { buffer, count ->
            trySend(buffer.copyOf(count))
        }
        awaitClose { stop() }
    }.buffer(Channel.CONFLATED)
}

internal fun captureBufferFrames(config: CaptureConfig, minBufferBytes: Int): Int {
    val minFrames = minBufferBytes.ceilDiv(Short.SIZE_BYTES)
    return maxOf(config.bufferFrames, minFrames)
}

private fun CaptureConfig.toAudioFormat(): AudioFormat {
    return AudioFormat.Builder()
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()
}

private fun Int.ceilDiv(other: Int): Int = (this + other - 1) / other

private const val STOP_JOIN_TIMEOUT_MS = 500L
