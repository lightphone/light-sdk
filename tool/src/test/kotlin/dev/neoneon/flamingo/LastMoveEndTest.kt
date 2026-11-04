package dev.neoneon.flamingo

import dev.neoneon.chesskit.Square
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LastMoveEndTest {

    private val e2e4 = Square("e2") to Square("e4")

    @Test
    fun theSquareAMoveLeftIsItsFromEnd() {
        assertEquals(LastMoveEnd.From, lastMoveEnd(Square("e2"), e2e4))
    }

    @Test
    fun theSquareAMoveReachedIsItsToEnd() {
        assertEquals(LastMoveEnd.To, lastMoveEnd(Square("e4"), e2e4))
    }

    @Test
    fun aSquareTheMoveDidNotTouchIsNeitherEnd() {
        assertNull(lastMoveEnd(Square("e3"), e2e4))
    }

    @Test
    fun withoutALastMoveNoSquareIsAnEnd() {
        assertNull(lastMoveEnd(Square("e2"), null))
    }
}
