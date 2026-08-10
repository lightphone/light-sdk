package com.thelightphone.sleeptrainer

import kotlin.test.Test
import kotlin.test.assertEquals

class SleepTrainerLogicTest {

    @Test
    fun `formatTime formats zero correctly`() {
        assertEquals("00:00", FerberSchedule.formatTime(0))
    }

    @Test
    fun `formatTime formats seconds correctly`() {
        assertEquals("00:45", FerberSchedule.formatTime(45))
    }

    @Test
    fun `formatTime formats minutes correctly`() {
        assertEquals("01:00", FerberSchedule.formatTime(60))
        assertEquals("02:30", FerberSchedule.formatTime(150))
    }

    @Test
    fun `Ferber intervals follow Day 1 schedule then repeat 15`() {
        assertEquals(2, FerberSchedule.getIntervalMinutes(0))
        assertEquals(5, FerberSchedule.getIntervalMinutes(1))
        assertEquals(10, FerberSchedule.getIntervalMinutes(2))
        assertEquals(15, FerberSchedule.getIntervalMinutes(3))
        assertEquals(15, FerberSchedule.getIntervalMinutes(4)) // Infinite 15
        assertEquals(15, FerberSchedule.getIntervalMinutes(100)) // Infinite 15
    }
}
