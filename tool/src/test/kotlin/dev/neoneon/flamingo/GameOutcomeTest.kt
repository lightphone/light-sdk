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

class GameOutcomeTest {

    // Advances a board the way GameViewViewModel.replayMove does, so what these tests read off
    // Board.state is what the screen really sees — chesskit only judges checkmate on a board
    // moved through, never on one whose position was set wholesale.
    private fun boardAfter(vararg lans: String, from: Position? = null): Board {
        val board = if (from != null) Board(from) else Board()
        for (lan in lans) {
            board.move(pieceAt = Square(lan.substring(0, 2)), to = Square(lan.substring(2, 4)))
        }
        return board
    }

    private fun move(id: String, resignPlayerID: String? = null) = Move(
        moveNumber = 1,
        lan = if (resignPlayerID == null) "e2e4" else null,
        fenAfter = "8/8/8/8/8/8/8/8 w - - 0 1",
        timestamp = "2026-01-01T00:00:00Z",
        id = id,
        resignPlayerID = resignPlayerID,
    )

    @Test
    fun readsCheckmateAsAWinForTheOtherColor() {
        // Fool's mate: 1. f3 e5 2. g4 Qh4#.
        val board = boardAfter("f2f3", "e7e5", "g2g4", "d8h4")

        assertEquals(Board.State.checkmate(Piece.Color.white), board.state)
        val outcome = gameOutcome(board.state, resignedColor = null)
        assertEquals(GameOutcome.Checkmate(Piece.Color.black), outcome)
        assertEquals("Checkmate, black won", outcome!!.label)
    }

    @Test
    fun readsStalemateAsItsOwnOutcomeRatherThanAPlainDraw() {
        // Black king boxed on h8 with no legal move; Qg5-g6 takes away the last one without check.
        val board = boardAfter("g5g6", from = Position("7k/8/8/6Q1/8/8/8/K7 w - - 0 1")!!)

        assertEquals(Board.State.draw(Board.State.DrawReason.stalemate), board.state)
        val outcome = gameOutcome(board.state, resignedColor = null)
        assertEquals(GameOutcome.Stalemate, outcome)
        assertEquals("Stalemate, a draw", outcome!!.label)
    }

    @Test
    fun readsOtherDrawsWithTheirReason() {
        // Kxh1 takes the last piece off the board, leaving two bare kings.
        val board = boardAfter("h2h1", from = Position("8/8/8/8/8/8/7k/K6R b - - 0 1")!!)

        val outcome = gameOutcome(board.state, resignedColor = null)
        assertEquals(GameOutcome.Drawn(Board.State.DrawReason.insufficientMaterial), outcome)
        assertEquals("Draw, too few pieces", outcome!!.label)
    }

    @Test
    fun hasNoOutcomeWhileTheGameIsStillOn() {
        assertNull(gameOutcome(boardAfter("e2e4").state, resignedColor = null))
        // Check isn't an ending either: Rg1-h1+ leaves black to move out of it.
        val checked = boardAfter("g1h1", from = Position("7k/8/8/8/8/8/8/K5R1 w - - 0 1")!!)
        assertNull(gameOutcome(checked.state, resignedColor = null))
    }

    @Test
    fun anAgreementEndsAnOtherwiseUnremarkablePosition() {
        // Like a resignation, agreeing writes no move — chesskit sees a live board and would
        // report no outcome at all, so agreement has to be carried in beside it.
        val board = boardAfter("e2e4")

        assertEquals(Board.State.active, board.state)
        assertNull(gameOutcome(board.state, resignedColor = null, agreedDraw = false))
        assertEquals(
            GameOutcome.Drawn(Board.State.DrawReason.agreement),
            gameOutcome(board.state, resignedColor = null, agreedDraw = true),
        )
    }

    @Test
    fun aResignationOutranksAnAgreement() {
        // A game can only be given up once; whichever of the two came second didn't happen.
        val board = boardAfter("e2e4")

        assertEquals(
            GameOutcome.Resigned(Piece.Color.black),
            gameOutcome(board.state, resignedColor = Piece.Color.black, agreedDraw = true),
        )
    }

    @Test
    fun aResignationEndsAnOtherwiseUnremarkablePosition() {
        // The resign log entry carries no move, so the board is still active when it happens —
        // the outcome can only come from the resigning color.
        val board = boardAfter("e2e4")

        assertEquals(Board.State.active, board.state)
        val outcome = gameOutcome(board.state, resignedColor = Piece.Color.white)
        assertEquals(GameOutcome.Resigned(Piece.Color.white), outcome)
        assertEquals("White resigned", outcome!!.label)
    }

    @Test
    fun findsTheResigningColorOnTheMoveLog() {
        val moves = listOf(move("m1"), move("m2", resignPlayerID = "BLACK-ID"))

        assertEquals(
            Piece.Color.black,
            moves.resignedColor(whitePlayerId = "white-id", blackPlayerId = "black-id"),
        )
    }

    @Test
    fun findsNoResignationWhenNobodyResigned() {
        assertNull(listOf(move("m1")).resignedColor("white-id", "black-id"))
        assertNull(emptyList<Move>().resignedColor("white-id", "black-id"))
    }

    @Test
    fun matchesALiveResignFrameToASeat() {
        // The socket frame names a player just like the log entry does — the server echoes ids
        // back uppercase, which is the whole reason this goes through samePlayer.
        assertEquals(
            Piece.Color.white,
            colorOfPlayer("WHITE-ID", whitePlayerId = "white-id", blackPlayerId = "black-id"),
        )
        assertNull(colorOfPlayer("a-stranger", whitePlayerId = "white-id", blackPlayerId = "black-id"))
    }

    @Test
    fun anUnfilledSeatIsNotTheResigner() {
        // Both seats null is the state an invite sits in before anyone joins. A null-matches-null
        // comparison here would report white as having resigned every such game.
        val moves = listOf(move("m1", resignPlayerID = "someone"))

        assertNull(moves.resignedColor(whitePlayerId = null, blackPlayerId = null))
    }

    @Test
    fun theServerRecordsMateStalemateResignationAndAgreement() {
        assertTrue(GameOutcome.Checkmate(Piece.Color.white).isRecordedByServer)
        assertTrue(GameOutcome.Stalemate.isRecordedByServer)
        assertTrue(GameOutcome.Resigned(Piece.Color.black).isRecordedByServer)
        assertTrue(GameOutcome.Drawn(Board.State.DrawReason.agreement).isRecordedByServer)
    }

    @Test
    fun theServerDoesNotRecordTheDrawsChesskitFindsOnItsOwn() {
        assertFalse(GameOutcome.Drawn(Board.State.DrawReason.repetition).isRecordedByServer)
        assertFalse(GameOutcome.Drawn(Board.State.DrawReason.fiftyMoves).isRecordedByServer)
        assertFalse(GameOutcome.Drawn(Board.State.DrawReason.insufficientMaterial).isRecordedByServer)
    }
}
