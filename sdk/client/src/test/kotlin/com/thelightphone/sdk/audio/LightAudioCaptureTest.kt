package com.thelightphone.sdk.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class LightAudioCaptureTest {
    @Test
    fun captureBufferFramesUsesConfigWhenAboveMinimum() {
        val config = CaptureConfig(bufferFrames = 512)

        assertEquals(512, captureBufferFrames(config, minBufferBytes = 256))
    }

    @Test
    fun captureBufferFramesUsesMinBufferWhenAboveConfig() {
        val config = CaptureConfig(bufferFrames = 128)

        assertEquals(512, captureBufferFrames(config, minBufferBytes = 1024))
    }
}
