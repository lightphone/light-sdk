package com.thelightphone.audiodemo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToneScreenTest {
    @Test
    fun equalTemperamentUsesA440AndOctaves() {
        assertEquals(440.0, noteFrequency(69))
        assertEquals(880.0, noteFrequency(81))
        assertTrue(noteFrequency(60) in 261.62..261.63)
    }

    @Test
    fun pianoSpansC4ToB4WithCorrectlyPlacedBlackKeys() {
        val vm = ToneViewModel()

        assertEquals(listOf("C4", "D4", "E4", "F4", "G4", "A4", "B4"), vm.whiteKeys.map { it.label })
        assertEquals(listOf(60, 62, 64, 65, 67, 69, 71), vm.whiteKeys.map { it.midiNote })

        assertEquals(listOf("C#4", "D#4", "F#4", "G#4", "A#4"), vm.blackKeys.map { it.note.label })
        assertEquals(listOf(0, 1, 3, 4, 5), vm.blackKeys.map { it.afterWhiteIndex })

        val midi = (vm.whiteKeys.map { it.midiNote } + vm.blackKeys.map { it.note.midiNote })
        assertTrue(midi.all { it in 60..71 })
    }
}
