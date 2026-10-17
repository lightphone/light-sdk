package dev.neoneon.flamingo

import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The same fixtures as iOS's HotSeatGameModelTests, so the two clients are held to one set of rules.
class HotSeatGameModelTest {

    // 1.f3 e5 2.g4 Qh4# — black mates.
    private val foolsMate = listOf("f2f3", "e7e5", "g2g4", "d8h4")

    // 1.Nf3 Nf6 2.Ng1 Ng8, twice — the start position comes round a third time.
    private val repetition = listOf("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8")

    // Sam Loyd's ten-move stalemate: black, to move, has no legal move and is not in check.
    private val stalemate = listOf(
        "e2e3", "a7a5", "d1h5", "a8a6", "h5a5", "h7h5", "h2h4", "a6h6", "a5c7", "f7f6",
        "c7d7", "e8f7", "d7b7", "d8d3", "b7b8", "d3h7", "b8c8", "f7g6", "c8e6",
    )

    // White's h-pawn walks to g7, one capture from promoting on h8.
    private val beforePromotion = listOf("h2h4", "g7g5", "h4g5", "h7h6", "g5h6", "f8g7", "h6g7", "g8f6")

    private fun play(lan: String, on: HotSeatGameModel): Boolean =
        on.play(Square(lan.substring(0, 2)), Square(lan.substring(2, 4)))

    // MARK: A fresh game

    @Test
    fun freshGameIsWhiteToMoveFacingWhite() {
        val model = HotSeatGameModel()
        assertEquals(Piece.Color.white, model.sideToMove)
        assertEquals(Piece.Color.white, model.orientation)
        assertFalse(model.needsTurn)
        assertFalse(model.canTakeBack)
        assertNull(model.outcome)
        assertFalse(model.wasTrimmed)
    }

    // MARK: Moving

    @Test
    fun aMoveIsRecorded() {
        val model = HotSeatGameModel()
        assertTrue(play("e2e4", model))
        assertEquals(listOf("e2e4"), model.moves)
        assertEquals(Piece.Color.black, model.sideToMove)
        assertTrue(model.canTakeBack)
        assertEquals(Square("e2") to Square("e4"), model.lastMoveSquares)
    }

    @Test
    fun aMoveLeavesTheBoardFacingTheMoverUntilTurned() {
        val model = HotSeatGameModel()
        play("e2e4", model)
        assertEquals(Piece.Color.white, model.orientation)
        assertTrue(model.needsTurn)

        model.turnToSideToMove()
        assertEquals(Piece.Color.black, model.orientation)
        assertFalse(model.needsTurn)
    }

    @Test
    fun eitherColorMovesOnItsTurn() {
        val model = HotSeatGameModel()
        play("e2e4", model)
        model.turnToSideToMove()
        assertTrue(play("e7e5", model))
        assertEquals(listOf("e2e4", "e7e5"), model.moves)
    }

    @Test
    fun aMoveOutOfTurnIsIgnored() {
        val model = HotSeatGameModel()
        assertFalse(play("e7e5", model))
        assertTrue(model.moves.isEmpty())
        assertTrue(model.legalTargets(Square("e7")).isEmpty())
    }

    @Test
    fun anIllegalMoveIsIgnored() {
        val model = HotSeatGameModel()
        assertFalse(play("e2e5", model))
        assertTrue(model.moves.isEmpty())
    }

    @Test
    fun aPromotionIsRecordedOnlyOnceThePieceIsPicked() {
        val model = HotSeatGameModel(beforePromotion)
        assertFalse(play("g7h8", model))
        assertEquals(beforePromotion, model.moves)
        assertTrue(model.pendingPromotion != null)
        assertFalse(model.needsTurn)
        assertNull(model.lastMoveSquares)

        assertTrue(model.completePromotion(Piece.Kind.queen))
        assertEquals("g7h8q", model.moves.last())
        assertNull(model.pendingPromotion)
        assertTrue(model.needsTurn)
    }

    @Test
    fun cancellingAPromotionPutsThePawnBack() {
        val model = HotSeatGameModel(beforePromotion)
        play("g7h8", model)

        model.cancelPromotion()
        assertNull(model.pendingPromotion)
        assertEquals(beforePromotion, model.moves)
        assertEquals(Piece.Kind.pawn, model.position.piece(at = Square("g7"))?.kind)
        assertEquals(Piece.Color.white, model.sideToMove)
    }

    // MARK: Taking back

    @Test
    fun takeBackRepeatsDownToTheStart() {
        val model = HotSeatGameModel(listOf("e2e4", "e7e5", "g1f3"))
        assertTrue(model.takeBack())
        assertEquals(listOf("e2e4", "e7e5"), model.moves)
        model.takeBack()
        model.takeBack()
        assertTrue(model.moves.isEmpty())
        assertFalse(model.canTakeBack)
        assertEquals(Position.standard, model.position)

        assertFalse(model.takeBack())
        assertTrue(model.moves.isEmpty())
    }

    @Test
    fun takeBackKeepsTheBoardFacingAsItWasSoTheTurnCanPlay() {
        val model = HotSeatGameModel(listOf("e2e4"))
        assertEquals(Piece.Color.black, model.orientation)

        model.takeBack()
        assertEquals(Piece.Color.white, model.sideToMove)
        assertEquals(Piece.Color.black, model.orientation)
        assertTrue(model.needsTurn)
    }

    @Test
    fun aNewMoveAfterATakeBackContinuesFromThere() {
        val model = HotSeatGameModel(listOf("e2e4", "e7e5"))
        model.takeBack()
        play("c7c5", model)
        assertEquals(listOf("e2e4", "c7c5"), model.moves)
    }

    @Test
    fun takeBackDuringAPromotionDropsOnlyTheUnfinishedMove() {
        val model = HotSeatGameModel(beforePromotion)
        play("g7h8", model)
        assertTrue(model.canTakeBack)

        assertFalse(model.takeBack())
        assertEquals(beforePromotion, model.moves)
        assertNull(model.pendingPromotion)
        assertEquals(Piece.Kind.pawn, model.position.piece(at = Square("g7"))?.kind)
    }

    @Test
    fun takeBackReopensAFinishedGame() {
        val model = HotSeatGameModel(foolsMate)
        assertFalse(play("e1f2", model))

        model.takeBack()
        assertNull(model.outcome)
        assertTrue(model.legalTargets(Square("d8")).isNotEmpty())
    }

    // MARK: Reset

    @Test
    fun resetRestoresTheStart() {
        val model = HotSeatGameModel(listOf("e2e4", "e7e5"))
        model.reset()
        assertTrue(model.moves.isEmpty())
        assertEquals(Position.standard, model.position)
        assertFalse(model.canTakeBack)
        assertNull(model.lastMoveSquares)
    }

    @Test
    fun resetFromBlacksTurnLeavesTheTurnBackToWhite() {
        val model = HotSeatGameModel(listOf("e2e4"))
        model.reset()
        assertEquals(Piece.Color.black, model.orientation)
        assertTrue(model.needsTurn)
    }

    // MARK: Reloading

    @Test
    fun reloadRestoresThePositionAndFacesTheSideToMove() {
        val first = HotSeatGameModel()
        play("e2e4", first)
        first.turnToSideToMove()
        play("e7e5", first)

        val reloaded = HotSeatGameModel(first.moves)
        assertEquals(listOf("e2e4", "e7e5"), reloaded.moves)
        assertEquals(first.position, reloaded.position)
        assertEquals(Piece.Color.white, reloaded.orientation)
        assertFalse(reloaded.needsTurn)
        assertEquals(Square("e7") to Square("e5"), reloaded.lastMoveSquares)
    }

    @Test
    fun reloadKeepsTheRepetitionHistory() {
        val model = HotSeatGameModel(repetition)
        assertEquals(GameOutcome.Drawn(Board.State.DrawReason.repetition), model.outcome)
    }

    @Test
    fun aRepetitionCanBeCompletedByAMoveAfterAReload() {
        val model = HotSeatGameModel(repetition.dropLast(1))
        assertNull(model.outcome)
        assertTrue(play("f6g8", model))
        assertEquals(GameOutcome.Drawn(Board.State.DrawReason.repetition), model.outcome)
        assertTrue(model.legalTargets(Square("g1")).isEmpty())
    }

    @Test
    fun reloadCutsACorruptListAtTheLastLegalMove() {
        val model = HotSeatGameModel(listOf("e2e4", "e2e4", "d7d5"))
        assertEquals(listOf("e2e4"), model.moves)
        assertTrue(model.wasTrimmed)
    }

    @Test
    fun reloadDropsAMoveOutOfTurn() {
        val model = HotSeatGameModel(listOf("e7e5"))
        assertTrue(model.moves.isEmpty())
        assertTrue(model.wasTrimmed)
    }

    @Test
    fun reloadDropsSomethingThatIsNotAMove() {
        assertEquals(listOf("e2e4"), HotSeatGameModel(listOf("e2e4", "zz99")).moves)
    }

    @Test
    fun reloadDropsAPromotionWithoutItsPiece() {
        assertEquals(beforePromotion, HotSeatGameModel(beforePromotion + "g7h8").moves)
    }

    @Test
    fun reloadDropsAPromotionPieceOnAnOrdinaryMove() {
        assertEquals(listOf("e2e4"), HotSeatGameModel(listOf("e2e4", "e7e5q")).moves)
    }

    @Test
    fun reloadKeepsACompletedPromotion() {
        val model = HotSeatGameModel(beforePromotion + "g7h8n")
        assertEquals(Piece.Kind.knight, model.position.piece(at = Square("h8"))?.kind)
        assertFalse(model.wasTrimmed)
    }

    @Test
    fun reloadDropsMovesPlayedAfterTheGameEnded() {
        // Nothing after a threefold repetition counts, legal or not.
        assertEquals(repetition, HotSeatGameModel(repetition + "e2e4").moves)
    }

    // MARK: Game end

    @Test
    fun checkmateNamesTheWinnerAndLocksTheBoard() {
        val model = HotSeatGameModel(foolsMate)
        assertEquals(GameOutcome.Checkmate(Piece.Color.black), model.outcome)
        assertTrue(model.legalTargets(Square("e1")).isEmpty())
        assertEquals(Square("e1"), model.kingInCheck)
        // The mated side is to move, so that is who the board faces.
        assertEquals(Piece.Color.white, model.orientation)
    }

    @Test
    fun stalemateIsADraw() {
        assertEquals(GameOutcome.Stalemate, HotSeatGameModel(stalemate).outcome)
    }

    @Test
    fun takeBackAndResetStayAvailableAfterTheGameEnds() {
        assertTrue(HotSeatGameModel(foolsMate).canTakeBack)
    }

    // MARK: Saving to the archive

    @Test
    fun onlyAFinishedGameCanBeSavedToTheArchive() {
        assertFalse(HotSeatGameModel().canSaveToArchive)
        assertFalse(HotSeatGameModel(listOf("e2e4", "e7e5")).canSaveToArchive)
        assertTrue(HotSeatGameModel(foolsMate).canSaveToArchive)
        assertTrue(HotSeatGameModel(stalemate).canSaveToArchive)
        assertTrue(HotSeatGameModel(repetition).canSaveToArchive)
    }

    @Test
    fun aPromotionWaitingForItsPieceCannotBeSaved() {
        val model = HotSeatGameModel(beforePromotion)
        play("g7h8", model)
        assertFalse(model.canSaveToArchive)
    }

    @Test
    fun takingBackTheLastMoveOfAFinishedGameTakesSavingAway() {
        val model = HotSeatGameModel(foolsMate)
        model.takeBack()
        assertFalse(model.canSaveToArchive)
    }

    // MARK: Replay

    @Test
    fun aGameWithoutMovesReplaysAsTheStartPosition() {
        val frames = HotSeatGame(moves = emptyList()).replayFrames()
        assertEquals(1, frames.size)
        assertEquals(Board().position, frames.single().position)
        assertNull(frames.single().lastMove)
    }

    @Test
    fun replayHasTheStartThenOnePositionPerMove() {
        val frames = HotSeatGame(moves = foolsMate).replayFrames()
        assertEquals(5, frames.size)
        assertEquals(Board().position, frames.first().position)
        assertNull(frames.first().lastMove)
        assertEquals(Square("f2") to Square("f3"), frames[1].lastMove)
        assertEquals(HotSeatGameModel(foolsMate).position, frames.last().position)
    }

    @Test
    fun replayMarksTheMateOnItsLastFrame() {
        val last = HotSeatGame(moves = foolsMate).replayFrames().last()
        assertEquals(Square("d8") to Square("h4"), last.lastMove)
        assertEquals(Square("e1"), last.checkedKingSquare)
    }

    @Test
    fun replayPlaysAPromotion() {
        val frames = HotSeatGame(moves = beforePromotion + "g7h8q").replayFrames()
        assertEquals(beforePromotion.size + 2, frames.size)
        assertEquals(
            Piece(Piece.Kind.queen, Piece.Color.white, Square("h8")),
            frames.last().position.piece(at = Square("h8")),
        )
    }

    @Test
    fun replayStopsAtTheFirstIllegalMove() {
        assertEquals(2, HotSeatGame(moves = listOf("e2e4", "e2e4", "e7e5")).replayFrames().size)
    }

    @Test
    fun replayStopsWhereTheGameEnded() {
        assertEquals(foolsMate.size + 1, HotSeatGame(moves = foolsMate + "a2a3").replayFrames().size)
        assertEquals(repetition.size + 1, HotSeatGame(moves = repetition + "e2e4").replayFrames().size)
    }
}
