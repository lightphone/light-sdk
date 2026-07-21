package com.thelightphone.audiodemo

import kotlin.test.Test
import kotlin.test.assertEquals

class RecorderScreenTest {
    @Test
    fun formatsElapsedTime() {
        assertEquals("0:00", formatRecorderTime(0L))
        assertEquals("1:05", formatRecorderTime(65_999L))
    }
}
