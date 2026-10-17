package dev.neoneon.flamingo

import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Square
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The positions a finished game is stepped back through, and the cursor into them.
 *
 * Fool's mate is the fixture throughout: four moves, so the log holds five frames, and the mating
 * move is a long diagonal that's easy to tell apart from the ones before it.
 */
class ReplayLogTest {

    // Plays the given moves on a fresh board, recording every position it passes through — the
    // opening one included, exactly as loadExistingGame does before replaying the server's record.
    private fun logOf(vararg lans: String): ReplayLog {
        val board = Board()
        val log = ReplayLog()
        log.record(ReplayFrame(position = board.position))

        for (lan in lans) {
            val move = board.move(
                pieceAt = Square(lan.substring(0, 2)),
                to = Square(lan.substring(2, 4)),
            )!!
            log.record(
                ReplayFrame(
                    position = board.position,
                    lastMove = move.start to move.end,
                    checkedKingSquare = checkedKingSquare(board.state, board.position),
                )
            )
        }
        return log
    }

    private fun foolsMate() = logOf("f2f3", "e7e5", "g2g4", "d8h4")

    @Test
    fun aFinishedGameOpensOnThePositionItEndedOn() {
        // The regression this file exists for: the cursor used to be read off the published state
        // rather than the log, so it never left 0 and a mated player was shown the opening
        // position — with the mating move's arrow still drawn over it.
        val log = foolsMate()

        assertEquals(5, log.size)
        assertEquals(4, log.index)
        assertEquals(Square.d8 to Square.h4, log.current?.lastMove)
        assertEquals(Square.e1, log.current?.checkedKingSquare)
    }

    @Test
    fun steppingBackWalksThroughTheMovesInTurn() {
        val log = foolsMate()

        assertTrue(log.step(forward = false))
        assertEquals(3, log.index)
        assertEquals(Square.g2 to Square.g4, log.current?.lastMove)

        assertTrue(log.step(forward = false))
        assertEquals(Square.e7 to Square.e5, log.current?.lastMove)
    }

    @Test
    fun theOpeningPositionCarriesNoArrow() {
        // Frames are taken whole, so "Start" shows a board no one has moved on yet rather than
        // borrowing the last move played from the frame the game ended on.
        val log = foolsMate()
        repeat(4) { log.step(forward = false) }

        assertEquals(0, log.index)
        assertNull(log.current?.lastMove)
        assertNull(log.current?.checkedKingSquare)
    }

    @Test
    fun steppingStopsAtEitherEnd() {
        val log = foolsMate()

        assertFalse(log.step(forward = true), "already on the last frame")

        repeat(4) { log.step(forward = false) }
        assertFalse(log.step(forward = false), "already on the first frame")
        assertEquals(0, log.index)
    }

    @Test
    fun aRepublishOfTheSamePositionAddsNothing() {
        // Most publishes change something other than the position — a selection, a notification —
        // and StateFlow.update may run its lambda more than once besides.
        val log = foolsMate()
        val position = log.current!!.position

        repeat(3) { log.record(ReplayFrame(position = position)) }

        assertEquals(5, log.size)
        assertEquals(4, log.index)
    }

    @Test
    fun aRepublishDoesNotDragTheCursorBackToTheEnd() {
        // A player who has stepped back stays where they are: re-publishing the state (the board
        // is untouched once a game is over) must not yank them to the closing position.
        val log = foolsMate()
        val closingPosition = log.current!!.position
        log.step(forward = false)
        val stepped = log.current!!.position

        // The board is frozen once a game is over, so every later publish re-records the closing
        // position — which the log already holds, and must not take for news.
        log.record(ReplayFrame(position = closingPosition))

        assertEquals(3, log.index)
        assertEquals(stepped, log.current?.position)
    }

    @Test
    fun rebuildingTheLogSendsTheCursorBackToTheStart() {
        // A re-sync rebuilds the board from the server's record, so the frames the old cursor
        // pointed into are gone.
        val log = foolsMate()
        log.clear()

        assertEquals(0, log.size)
        assertEquals(0, log.index)
        assertNull(log.current)
    }

    @Test
    fun thePositionLabelNamesTheMoveThatProducedIt() {
        assertEquals("Start", replayPositionLabel(frameIndex = 0, frameCount = 5))
        assertEquals("Move 2 of 4", replayPositionLabel(frameIndex = 2, frameCount = 5))
        assertEquals("Move 4 of 4", replayPositionLabel(frameIndex = 4, frameCount = 5))
    }

    @Test
    fun aGameThatEndedBeforeAnyoneMovedHasOneFrameAndNowhereToStep() {
        val log = logOf()

        assertEquals(1, log.size)
        assertFalse(log.step(forward = false))
        assertFalse(log.step(forward = true))
    }
}
