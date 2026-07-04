package dev.neoneon.chesskit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class BoardTests {

    @Test
    fun updatePosition() {
        val board = Board()
        assertEquals(Position.standard, board.position)
        assertEquals(Board.State.active, board.state)

        board.update(Position.complex)
        assertEquals(Position.complex, board.position)
        assertEquals(Board.State.active, board.state)

        board.update(Position.test)
        board.update(Position.test)
        board.update(Position.test)
        assertEquals(Position.test, board.position)
        assertEquals(Board.State.draw(Board.State.DrawReason.repetition), board.state)

        board.update(Position.test, resetPositionCounts = true)
        assertEquals(Board.State.active, board.state)
    }

    @Test
    fun enPassant() {
        val board = Board(Position.ep)
        val ep = assertNotNull(board.position.enPassant)

        val capturingPiece = assertNotNull(board.position.piece(at = Square.f4))
        assertTrue(ep.couldBeCaptured(capturingPiece))

        val move = assertNotNull(board.move(Square.f4, ep.captureSquare))
        assertEquals(Move.Result.capture(ep.pawn), move.result)
    }

    @Test
    fun illegalEnPassant() {
        // fen position contains illegal en passant move
        val board = Board(Position("1nbqkbnr/1pp1pppp/8/r1Pp3K/p7/5P2/PP1PP1PP/RNBQ1BNR w k d6 0 8")!!)
        assertFalse(board.canMove(Square.c5, Square.d6))
    }

    @Test
    fun doubleEnPassant() {
        val board = Board(Position("kr6/2p5/8/1P1P4/8/1K6/8/8 b - - 0 1")!!)
        board.move(Square.c7, Square.c5)
        // after this move only 1 out of 2 pawns can execute enPassant
        assertFalse(board.canMove(Square.b5, Square.c6))
        assertTrue(board.canMove(Square.d5, Square.c6))
        assertTrue(board.position.enPassantIsPossible)
    }

    @Test
    fun promotion() {
        for (color in Piece.Color.entries) {
            val pawnStart = if (color == Piece.Color.white) Square.e7 else Square.e2
            val pawnEnd = if (color == Piece.Color.white) Square.e8 else Square.e1

            val king = Piece(Piece.Kind.pawn, color.opposite, Square.a7)
            val pawn = Piece(Piece.Kind.pawn, color, pawnStart)
            val queen = Piece(Piece.Kind.queen, color, pawnEnd)
            val board = Board(Position(pieces = listOf(pawn, king), sideToMove = color))

            val attemptedMove = board.move(pawnStart, pawnEnd)

            val state = board.state
            if (state is Board.State.promotion) {
                val promotionMove = board.completePromotion(state.move, Piece.Kind.queen)
                assertEquals(Move.Result.move, promotionMove.result)
                assertEquals(queen, promotionMove.promotedPiece)
                assertEquals(pawnEnd, promotionMove.end)
                assertEquals(Board.State.active, board.state)
            } else {
                fail("Failed to trigger promotion for $attemptedMove")
            }
        }
    }

    @Test
    fun initializeWithPromotion() {
        for (color in Piece.Color.entries) {
            val pawnSquare = if (color == Piece.Color.white) Square.e8 else Square.e1

            val king = Piece(Piece.Kind.pawn, color.opposite, Square.a7)
            val pawn = Piece(Piece.Kind.pawn, color, pawnSquare)
            val queen = Piece(Piece.Kind.queen, color, pawnSquare)
            val board = Board(Position(pieces = listOf(pawn, king), sideToMove = color))

            val state = board.state
            if (state is Board.State.promotion) {
                val promotionMove = board.completePromotion(state.move, Piece.Kind.queen)
                assertEquals(Move.Result.move, promotionMove.result)
                assertEquals(queen, promotionMove.promotedPiece)
                assertEquals(pawnSquare, promotionMove.end)
                assertEquals(Board.State.active, board.state)
            } else {
                fail("Failed to identify promotion on $pawnSquare")
            }
        }
    }

    @Test
    fun fiftyMoveRule() {
        val board = Board(Position.fiftyMove)
        board.move(Square.f7, Square.f8)
        assertEquals(Board.State.draw(Board.State.DrawReason.fiftyMoves), board.state)
    }

    @Test
    fun insufficientMaterial() {
        val board = Board(Position("k7/b6P/8/8/8/8/8/K7 w - - 0 1")!!)
        val attemptedMove = board.move(Square.h7, Square.h8)
        val state = board.state
        if (state is Board.State.promotion) {
            board.completePromotion(state.move, Piece.Kind.bishop)
            assertEquals(Board.State.draw(Board.State.DrawReason.insufficientMaterial), board.state)
        } else {
            fail("Failed to trigger promotion for $attemptedMove")
        }
    }

    @Test
    fun insufficientMaterialScenarios() {
        // different promotions
        val fen = "k7/7P/8/8/8/8/8/K7 w - - 0 1"

        val validPieces = listOf(Piece.Kind.rook, Piece.Kind.queen)
        val invalidPieces = listOf(Piece.Kind.bishop, Piece.Kind.knight)

        for (p in validPieces) {
            val board = Board(Position(fen)!!)
            val move = assertNotNull(board.move(Square.h7, Square.h8))

            board.completePromotion(move, p)
            assertFalse(board.position.hasInsufficientMaterial)
            assertEquals(Board.State.check(Piece.Color.black), board.state)
        }

        for (p in invalidPieces) {
            val board = Board(Position(fen)!!)
            val move = assertNotNull(board.move(Square.h7, Square.h8))

            board.completePromotion(move, p)
            assertTrue(board.position.hasInsufficientMaterial)
            assertEquals(Board.State.draw(Board.State.DrawReason.insufficientMaterial), board.state)
        }

        // opposite color bishops vs same color bishops
        val fen2 = "k5B1/b7/1b6/8/8/8/8/K7 w - - 0 1"
        val fen3 = "k5B1/1b6/2b5/8/8/8/8/K7 w - - 0 1"

        val board2 = Board(Position(fen2)!!)
        val board3 = Board(Position(fen3)!!)

        assertFalse(board2.position.hasInsufficientMaterial)
        assertEquals(Board.State.active, board2.state)
        assertTrue(board3.position.hasInsufficientMaterial)
        assertEquals(Board.State.draw(Board.State.DrawReason.insufficientMaterial), board3.state)

        // before and after king takes Queen
        val fen4 = "k7/1Q6/8/8/8/8/8/K7 w - - 0 1"
        val board4 = Board(Position(fen4)!!)

        assertFalse(board4.position.hasInsufficientMaterial)
        assertEquals(Board.State.check(Piece.Color.black), board4.state)
        board4.move(Square.a8, Square.b7)
        assertTrue(board4.position.hasInsufficientMaterial)
        assertEquals(Board.State.draw(Board.State.DrawReason.insufficientMaterial), board4.state)
    }

    @Test
    fun threefoldRepetition() {
        val board = Board(Position.standard)

        board.move(Square.e2, Square.e4)
        board.move(Square.e7, Square.e5) // 1st time position occurs

        board.move(Square.g1, Square.f3)
        board.move(Square.g8, Square.f6)

        board.move(Square.f3, Square.g1)
        board.move(Square.f6, Square.g8) // 2nd time position occurs

        board.move(Square.g1, Square.f3)
        board.move(Square.g8, Square.f6)

        board.move(Square.f3, Square.g1)
        board.move(Square.f6, Square.g8) // 3rd time position occurs

        assertEquals(Board.State.draw(Board.State.DrawReason.repetition), board.state)
    }

    @Test
    fun legalMovesForNonexistentPiece() {
        val board = Board(Position.standard)
        // no piece at d4
        val legalMoves = board.legalMoves(forPieceAt = Square.d4)
        assertTrue(legalMoves.isEmpty())
    }

    @Test
    fun legalPawnMoves() {
        val board = Board(Position.standard)
        val legalC2PawnMoves = board.legalMoves(forPieceAt = Square.c2)
        assertEquals(2, legalC2PawnMoves.size)
        assertTrue(legalC2PawnMoves.contains(Square.c3))
        assertTrue(legalC2PawnMoves.contains(Square.c4))
        assertTrue(board.canMove(Square.c2, Square.c3))
        assertTrue(board.canMove(Square.c2, Square.c4))
        assertFalse(board.canMove(Square.c2, Square.c5))

        val legalF7PawnMoves = board.legalMoves(forPieceAt = Square.f7)
        assertEquals(2, legalF7PawnMoves.size)
        assertTrue(legalF7PawnMoves.contains(Square.f6))
        assertTrue(legalF7PawnMoves.contains(Square.f5))
        assertTrue(board.canMove(Square.f7, Square.f6))
        assertTrue(board.canMove(Square.f7, Square.f5))
        assertFalse(board.canMove(Square.f7, Square.f4))

        // test pawns on starting rank can't hop over pieces
        val position = Position("rnbqkbnr/p1p1p1pp/1pPp4/8/8/4PpP1/PP1P1P1P/RNBQKBNR w KQkq - 0 1")!!
        val b2 = Board(position)
        val legalF2PawnMoves = b2.legalMoves(forPieceAt = Square.f2)
        assertTrue(legalF2PawnMoves.isEmpty())

        val legalC7PawnMoves = b2.legalMoves(forPieceAt = Square.c7)
        assertTrue(legalC7PawnMoves.isEmpty())
    }

    @Test
    fun legalKnightMoves() {
        val position = Position("N6N/8/4N3/8/2N5/5N2/3N4/N6N w - - 0 1")!!
        val board = Board(position)

        assertTrue(board.canMove(Square.a8, Square.b6))
        assertTrue(board.canMove(Square.a8, Square.c7))

        assertTrue(board.canMove(Square.h8, Square.f7))
        assertTrue(board.canMove(Square.h8, Square.g6))

        assertTrue(board.canMove(Square.a1, Square.b3))
        assertTrue(board.canMove(Square.a1, Square.c2))

        assertTrue(board.canMove(Square.h1, Square.f2))
        assertTrue(board.canMove(Square.h1, Square.g3))

        assertTrue(board.canMove(Square.c4, Square.a3))
        assertTrue(board.canMove(Square.c4, Square.a5))
        assertTrue(board.canMove(Square.c4, Square.b2))
        assertTrue(board.canMove(Square.c4, Square.b6))
        assertFalse(board.canMove(Square.c4, Square.d2))
        assertTrue(board.canMove(Square.c4, Square.d6))
        assertTrue(board.canMove(Square.c4, Square.e3))
        assertTrue(board.canMove(Square.c4, Square.e5))

        assertTrue(board.canMove(Square.d2, Square.b1))
        assertTrue(board.canMove(Square.d2, Square.b3))
        assertFalse(board.canMove(Square.d2, Square.c4))
        assertTrue(board.canMove(Square.d2, Square.e4))
        assertFalse(board.canMove(Square.d2, Square.f3))
        assertTrue(board.canMove(Square.d2, Square.f1))
    }

    @Test
    fun legalBishopMoves() {
        val position = Position("5bBb/8/8/pPpPpPpP/8/8/8/BbB5 w - - 0 1")!!
        val board = Board(position)

        assertTrue(board.canMove(Square.a1, Square.d4))
        assertTrue(board.canMove(Square.a1, Square.e5))
        assertFalse(board.canMove(Square.a1, Square.f6))

        assertTrue(board.canMove(Square.b1, Square.e4))
        assertTrue(board.canMove(Square.b1, Square.f5))
        assertFalse(board.canMove(Square.b1, Square.g6))

        assertTrue(board.canMove(Square.c1, Square.f4))
        assertTrue(board.canMove(Square.c1, Square.g5))
        assertFalse(board.canMove(Square.c1, Square.h8))

        assertTrue(board.canMove(Square.f8, Square.d6))
        assertFalse(board.canMove(Square.f8, Square.c5))
        assertFalse(board.canMove(Square.f8, Square.b4))

        assertTrue(board.canMove(Square.g8, Square.e6))
        assertFalse(board.canMove(Square.g8, Square.d5))
        assertFalse(board.canMove(Square.g8, Square.c4))

        assertTrue(board.canMove(Square.h8, Square.f6))
        assertFalse(board.canMove(Square.h8, Square.e5))
        assertFalse(board.canMove(Square.h8, Square.d4))
    }

    @Test
    fun legalRookMoves() {
        val position = Position("r7/1r6/2r1p3/P7/p7/2R1P3/1R6/R7 w - - 0 1")!!
        val board = Board(position)

        listOf(Square.a4, Square.h1).forEach {
            assertTrue(board.canMove(Square.a1, it))
        }

        assertFalse(board.canMove(Square.a1, Square.a5))

        listOf(Square.a2, Square.b1, Square.b7, Square.h2).forEach {
            assertTrue(board.canMove(Square.b2, it))
        }

        assertFalse(board.canMove(Square.b2, Square.b8))
    }

    @Test
    fun legalQueenMoves() {
        val position = Position("7k/8/2pP4/3qq3/3QQ3/4pP2/8/K7 w - - 0 1")!!
        val board = Board(position)

        listOf(Square.b2, Square.c3, Square.e5).forEach { assertTrue(board.canMove(Square.d4, it)) }
        listOf(Square.e3, Square.c4, Square.b4).forEach { assertFalse(board.canMove(Square.d4, it)) }

        listOf(Square.d4, Square.f6, Square.g7).forEach { assertTrue(board.canMove(Square.e5, it)) }
        listOf(Square.d6, Square.e6, Square.g6).forEach { assertFalse(board.canMove(Square.e5, it)) }

        listOf(Square.d4, Square.e4, Square.a2).forEach { assertTrue(board.canMove(Square.d5, it)) }
        listOf(Square.c6, Square.e5, Square.d7).forEach { assertFalse(board.canMove(Square.d5, it)) }

        listOf(Square.d5, Square.e5, Square.h7).forEach { assertTrue(board.canMove(Square.e4, it)) }
        listOf(Square.e2, Square.d4, Square.f3).forEach { assertFalse(board.canMove(Square.e4, it)) }
    }

    @Test
    fun legalKingMoves() {
        val position = Position("8/8/8/4p3/4K3/8/8/8 w - - 0 1")!!
        val board = Board(position)

        listOf(Square.d3, Square.d5, Square.f3, Square.f5, Square.e3, Square.e5).forEach {
            assertTrue(board.canMove(Square.e4, it))
        }

        listOf(Square.d4, Square.f4).forEach {
            assertFalse(board.canMove(Square.e4, it))
        }
    }

    @Test
    fun captureMove() {
        val board = Board(Position("8/8/8/4p3/3P4/8/8/8 w - - 0 1")!!)
        val move = board.move(Square.d4, Square.e5)

        val capturedPiece = Piece(Piece.Kind.pawn, Piece.Color.black, Square.e5)
        assertEquals(Move.Result.capture(capturedPiece), move?.result)
    }

    @Test
    fun illegalMove() {
        val board = Board(Position.standard)
        val move = board.move(Square.d2, Square.d5)
        assertNull(move)
    }

    @Test
    fun checkMove() {
        val board = Board(Position("k7/7R/8/8/8/8/K7/8 w - - 0 1")!!)
        val move = board.move(Square.h7, Square.h8)
        assertEquals(Move.CheckState.check, move?.checkState)
        assertEquals(Board.State.check(Piece.Color.black), board.state)
    }

    @Test
    fun checkmateMove() {
        val board = Board(Position("k7/7R/6R1/8/8/8/K7/8 w - - 0 1")!!)
        val move = board.move(Square.g6, Square.g8)
        assertEquals(Move.CheckState.checkmate, move?.checkState)
        assertEquals(Board.State.checkmate(Piece.Color.black), board.state)
    }

    @Test
    fun sideToMove() {
        var position = Position.standard
        assertEquals(Piece.Color.white, position.sideToMove)

        position = position.movePieceAt(Square.e2, Square.e4).first
        assertEquals(Piece.Color.black, position.sideToMove)
    }

    @Test
    fun disambiguation() {
        val board = Board(Position("3r3r/8/4B3/R2n4/2B1Q2Q/8/8/R6Q w - - 0 1")!!)

        val r1a3 = board.move(Square.a1, Square.a3)
        val rdf8 = board.move(Square.d8, Square.f8)
        val qh4e1 = board.move(Square.h4, Square.e1)

        assertEquals("R1a3", r1a3?.san)
        assertEquals("Rdf8", rdf8?.san)
        assertEquals("Qh4e1", qh4e1?.san)

        val bf7 = board.move(Square.e6, Square.f7)
        assertEquals("Bf7", bf7?.san)

        val bfxd5 = board.move(Square.f7, Square.d5)
        assertEquals("Bfxd5", bfxd5?.san)
    }

    @Test
    fun print() {
        val board = Board()

        ChessKitConfiguration.printOptions = ChessKitConfiguration.printOptions.copy(mode = ChessKitConfiguration.PrintOptions.PrintMode.letter)
        assertEquals(
            """
            8 r n b q k b n r
            7 p p p p p p p p
            6 · · · · · · · ·
            5 · · · · · · · ·
            4 · · · · · · · ·
            3 · · · · · · · ·
            2 P P P P P P P P
            1 R N B Q K B N R
              a b c d e f g h
            """.trimIndent(),
            board.toString(),
        )

        ChessKitConfiguration.printOptions = ChessKitConfiguration.printOptions.copy(mode = ChessKitConfiguration.PrintOptions.PrintMode.graphic)
        assertEquals(
            """
            8 ♜ ♞ ♝ ♛ ♚ ♝ ♞ ♜
            7 ♟︎ ♟︎ ♟︎ ♟︎ ♟︎ ♟︎ ♟︎ ♟︎
            6 · · · · · · · ·
            5 · · · · · · · ·
            4 · · · · · · · ·
            3 · · · · · · · ·
            2 ♙ ♙ ♙ ♙ ♙ ♙ ♙ ♙
            1 ♖ ♘ ♗ ♕ ♔ ♗ ♘ ♖
              a b c d e f g h
            """.trimIndent(),
            board.toString(),
        )

        val bb = board.position.pieceSet.all
        assertEquals(
            """
            8 ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯
            7 ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯
            6 · · · · · · · ·
            5 · · · · · · · ·
            4 · · · · · · · ·
            3 · · · · · · · ·
            2 ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯
            1 ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯
              a b c d e f g h
            """.trimIndent(),
            bb.chessString(),
        )

        assertEquals(
            """
            ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯
            ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯
            · · · · · · · ·
            · · · · · · · ·
            · · · · · · · ·
            · · · · · · · ·
            ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯
            ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯ ⨯
            """.trimIndent(),
            bb.chessString(labelRanks = false, labelFiles = false),
        )
    }
}
