package com.thelightphone.sdk.audio

import android.media.AudioAttributes
import android.media.MediaRecorder
import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AudioTypesTest {
    @Test
    fun lightAudioUsageMapsToExpectedAudioAttributesSpec() {
        assertEquals(
            AudioAttributeSpec(
                usage = AudioAttributes.USAGE_MEDIA,
                contentType = AudioAttributes.CONTENT_TYPE_MUSIC
            ),
            LightAudioUsage.Music.toAudioAttributeSpec()
        )
        assertEquals(
            AudioAttributeSpec(
                usage = AudioAttributes.USAGE_MEDIA,
                contentType = AudioAttributes.CONTENT_TYPE_SPEECH
            ),
            LightAudioUsage.Speech.toAudioAttributeSpec()
        )
        assertEquals(
            AudioAttributeSpec(
                usage = AudioAttributes.USAGE_ALARM,
                contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION
            ),
            LightAudioUsage.Alarm.toAudioAttributeSpec()
        )
        assertEquals(
            AudioAttributeSpec(
                usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                contentType = AudioAttributes.CONTENT_TYPE_SPEECH
            ),
            LightAudioUsage.VoiceCall.toAudioAttributeSpec()
        )
    }

    @Test
    fun lightAudioUsageMappingsAreDistinct() {
        val specs = LightAudioUsage.entries.map { it.toAudioAttributeSpec() }

        assertEquals(specs.size, specs.toSet().size)
        assertNotEquals(LightAudioUsage.Music.toAudioAttributeSpec(), LightAudioUsage.Speech.toAudioAttributeSpec())
    }

    @Test
    fun configsAreConstructibleWithDefaults() {
        assertEquals(RecorderConfig(), RecorderConfig())
        assertEquals(CaptureConfig(), CaptureConfig())
        assertEquals(AudioCapabilities.Default, AudioCapabilities(DEFAULT_SAMPLE_RATE))
        assertEquals(LightMediaMetadata(title = "Title"), LightMediaMetadata(title = "Title"))
    }

    @Test
    fun recorderConfigDefaultsMapToMicRecordingConstants() {
        val config = RecorderConfig()

        assertEquals(MediaRecorder.AudioSource.MIC, config.source.toMediaRecorderSource())
        assertEquals(DEFAULT_SAMPLE_RATE, config.sampleRate)
        assertEquals(1, config.channels)
    }

    @Test
    fun onlyMusicAndSpeechPublishASessionUsage() {
        assertEquals("music", LightAudioUsage.Music.sessionUsageValue())
        assertEquals("speech", LightAudioUsage.Speech.sessionUsageValue())
        assertNull(LightAudioUsage.Alarm.sessionUsageValue())
        assertNull(LightAudioUsage.VoiceCall.sessionUsageValue())
    }

    @Test
    fun onlySpeechSeeksInFifteenSecondSteps() {
        assertEquals(15_000L, LightAudioUsage.Speech.seekIncrementMs())
        assertNull(LightAudioUsage.Music.seekIncrementMs())
        assertNull(LightAudioUsage.Alarm.seekIncrementMs())
        assertNull(LightAudioUsage.VoiceCall.seekIncrementMs())
    }

    @Test
    fun musicHidesSeekStepsFromExternalControllers() {
        assertEquals(
            setOf(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD),
            LightAudioUsage.Music.hiddenExternalPlayerCommands().toSet()
        )
    }

    @Test
    fun speechHidesPreviousAndNextFromExternalControllers() {
        assertEquals(
            setOf(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            ),
            LightAudioUsage.Speech.hiddenExternalPlayerCommands().toSet()
        )
    }
}
