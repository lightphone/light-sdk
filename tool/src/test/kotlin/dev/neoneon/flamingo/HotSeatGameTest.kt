package dev.neoneon.flamingo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HotSeatGameTest {

    @Test
    fun roundTrips() {
        val game = HotSeatGame(id = "game", startedAtMillis = 42, moves = listOf("e2e4", "e7e8q"))
        assertEquals(game, decodeHotSeatGame(encodeHotSeatGame(game)))
    }

    @Test
    fun roundTripsAnEmptyGame() {
        val game = HotSeatGame(id = "game", startedAtMillis = 42)
        assertEquals(game, decodeHotSeatGame(encodeHotSeatGame(game)))
    }

    @Test
    fun nothingStoredIsNoGame() {
        assertNull(decodeHotSeatGame(null))
    }

    @Test
    fun garbageIsNoGame() {
        assertNull(decodeHotSeatGame("not json"))
        assertNull(decodeHotSeatGame(""))
    }

    @Test
    fun anotherShapeIsNoGame() {
        assertNull(decodeHotSeatGame("""{"moves": "e2e4"}"""))
        assertNull(decodeHotSeatGame("""["e2e4"]"""))
    }

    @Test
    fun unknownKeysAreIgnored() {
        val decoded = decodeHotSeatGame("""{"id":"game","startedAtMillis":42,"moves":["e2e4"],"later":true}""")
        assertEquals(HotSeatGame(id = "game", startedAtMillis = 42, moves = listOf("e2e4")), decoded)
    }
}
