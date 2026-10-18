package dev.neoneon.flamingo

import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the game screen offers once a game is over: stepping back through the positions it passed
 * through, rather than sitting on the closing one with nothing to do.
 */
class ReplayStateTest {

    private fun state(
        outcome: GameOutcome? = null,
        frameIndex: Int = 0,
        frameCount: Int = 0,
    ) = GameViewViewModel.State(
        position = Position.standard,
        localColor = Piece.Color.white,
        outcome = outcome,
        frameIndex = frameIndex,
        frameCount = frameCount,
    )

    private val checkmate = GameOutcome.Checkmate(Piece.Color.white)

    @Test
    fun aGameStillBeingPlayedHasNothingToReplay() {
        // frameCount is 0 while the game is on: the positions are recorded, but offering to step
        // through a game you are still playing would just be a way to lose your place in it.
        assertFalse(state(outcome = null, frameCount = 0).canReplay)
    }

    @Test
    fun aFinishedGameWithMovesCanBeSteppedThrough() {
        assertTrue(state(outcome = checkmate, frameIndex = 7, frameCount = 8).canReplay)
    }

    @Test
    fun aGameThatEndedOnItsOpeningPositionHasNothingToStepThrough() {
        // One frame is the opening position alone — a game resigned before a piece moved. There is
        // no second position to step to, so the bar stays off.
        assertFalse(state(outcome = GameOutcome.Resigned(Piece.Color.black), frameCount = 1).canReplay)
    }

    @Test
    fun theFinalPositionHasNowhereToStepForward() {
        val last = state(outcome = checkmate, frameIndex = 7, frameCount = 8)
        assertFalse(last.canStepForward)
        assertTrue(last.canStepBack)
    }

    @Test
    fun theOpeningPositionHasNowhereToStepBack() {
        val first = state(outcome = checkmate, frameIndex = 0, frameCount = 8)
        assertFalse(first.canStepBack)
        assertTrue(first.canStepForward)
    }

    @Test
    fun aMiddlePositionCanStepEitherWay() {
        val middle = state(outcome = checkmate, frameIndex = 3, frameCount = 8)
        assertTrue(middle.canStepBack)
        assertTrue(middle.canStepForward)
    }

    @Test
    fun withNothingToReplayNeitherDirectionIsOffered() {
        assertFalse(state(outcome = null, frameIndex = 3, frameCount = 8).canStepBack)
        assertFalse(state(outcome = null, frameIndex = 3, frameCount = 8).canStepForward)
        assertFalse(state(outcome = GameOutcome.Resigned(Piece.Color.black), frameCount = 1).canStepForward)
    }

    @Test
    fun theLabelNamesTheOpeningPositionRatherThanMoveZero() {
        assertEquals("Start", state(outcome = checkmate, frameIndex = 0, frameCount = 8).replayLabel)
    }

    @Test
    fun theLabelCountsMovesNotPositions() {
        // 8 frames are the opening plus 7 moves, so the last one is move 7 — not move 8, and not
        // "7 of 8".
        assertEquals("Move 7 of 7", state(outcome = checkmate, frameIndex = 7, frameCount = 8).replayLabel)
        assertEquals("Move 3 of 7", state(outcome = checkmate, frameIndex = 3, frameCount = 8).replayLabel)
    }

    // MARK: Archiving

    private fun seated(outcome: GameOutcome?, isLoading: Boolean = false) = state(outcome = outcome).copy(
        isLoading = isLoading,
        playerId = "96596872-5fe3-4574-8684-aca1047afa23",
        whitePlayerId = "96596872-5FE3-4574-8684-ACA1047AFA23",
        blackPlayerId = "AB98F326-AA39-4F83-8E15-EA8ECABBFC05",
    )

    @Test
    fun aFinishedGameWeSitInCanBeArchived() {
        assertTrue(seated(outcome = checkmate).canArchive)
        assertTrue(seated(outcome = GameOutcome.Resigned(Piece.Color.black)).canArchive)
    }

    @Test
    fun aGameStillBeingPlayedCannotBeArchived() {
        assertFalse(seated(outcome = null).canArchive)
    }

    @Test
    fun aDrawTheServerDoesNotRecordCannotBeArchived() {
        // It stays `active` on the server, which would refuse the request.
        val repetition = GameOutcome.Drawn(Board.State.DrawReason.repetition)
        assertFalse(seated(outcome = repetition).canArchive)
    }

    @Test
    fun nothingCanBeArchivedBeforeTheRecordHasLoaded() {
        assertFalse(seated(outcome = checkmate, isLoading = true).canArchive)
    }

    @Test
    fun aGameWeDoNotSitInCannotBeArchived() {
        assertFalse(seated(outcome = checkmate).copy(playerId = "someone-else").canArchive)
    }
}
