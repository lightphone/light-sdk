package com.thelightphone.sdk.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Plays short mono 16-bit PCM buffers at [sampleRate] with transient audio
 * focus.
 *
 * Only one buffer sounds at a time; [play] re-triggers the voice. For polyphony,
 * create several voices and play across them so the platform can mix the tracks.
 */
class LightAudioVoice internal constructor(
    context: Context,
    private val usage: LightAudioUsage,
    private val sampleRate: Int,
) {
    private val focus = AudioFocusHelper(
        context = context,
        usage = usage,
        gainType = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        onFocusChange = { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                stop()
            }
        }
    )
    private var track: AudioTrack? = null
    private var trackFrames = 0
    private var released = false

    /**
     * Play [pcm] as mono 16-bit audio at the voice's sample rate, re-triggering
     * if the voice is already sounding.
     *
     * Does nothing for empty input, after [release], or when audio focus or
     * output is unavailable.
     */
    @Synchronized
    fun play(pcm: ShortArray) {
        if (released || pcm.isEmpty()) return
        if (!focus.request()) {
            Log.e(TAG, "Audio focus request denied")
            return
        }

        val audioTrack = obtainTrack(pcm.size) ?: run {
            focus.abandon()
            return
        }
        audioTrack.stopIfPlaying()
        // A reused static track's head sits at the end after playing; rewind
        // before overwriting the buffer so the replay is audible.
        if (audioTrack.playbackHeadPosition > 0) audioTrack.reloadStaticData()
        val written = audioTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        if (written <= 0) {
            Log.e(TAG, "AudioTrack write failed: $written")
            stop()
            return
        }
        audioTrack.notificationMarkerPosition = pcm.size
        audioTrack.play()
    }

    /** Stops the current buffer and abandons transient audio focus. */
    @Synchronized
    fun stop() {
        track?.stopIfPlaying()
        track?.flush()
        focus.abandon()
    }

    /** Permanently releases the voice and its audio track. Idempotent. */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        stop()
        track?.release()
        track = null
        trackFrames = 0
    }

    /** Reuses the track while the frame count is unchanged; rebuilds otherwise. */
    private fun obtainTrack(frames: Int): AudioTrack? {
        if (track != null && trackFrames == frames) return track

        track?.release()
        track = null
        trackFrames = 0

        val built = try {
            AudioTrack.Builder()
                .setAudioAttributes(usage.toAudioAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(frames * Short.SIZE_BYTES)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (exception: Exception) {
            Log.e(TAG, "AudioTrack build failed", exception)
            return null
        }
        if (built.state != AudioTrack.STATE_INITIALIZED &&
            built.state != AudioTrack.STATE_NO_STATIC_DATA
        ) {
            Log.e(TAG, "AudioTrack was not initialized: state=${built.state}")
            built.release()
            return null
        }
        built.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(audioTrack: AudioTrack) = focus.abandon()
            override fun onPeriodicNotification(audioTrack: AudioTrack) = Unit
        })
        track = built
        trackFrames = frames
        return built
    }
}

private fun AudioTrack.stopIfPlaying() {
    if (playState != AudioTrack.PLAYSTATE_STOPPED) stop()
}

private const val TAG = "LightAudioVoice"
