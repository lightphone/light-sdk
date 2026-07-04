package dev.neoneon.chesskit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PositionTests {

    @Test
    fun initializer() {
        val whitePawn = Piece(Piece.Kind.pawn, Piece.Color.white, Square.e5)
        val blackPawn = Piece(Piece.Kind.pawn, Piece.Color.black, Square.d5)

        val position1 = Position(
            pieces = listOf(whitePawn, blackPawn),
            sideToMove = Piece.Color.white,
            legalCastlings = LegalCastlings(),
            enPassant = EnPassant(blackPawn),
            clock = Clock(),
        )

        assertTrue(position1.enPassantIsPossible)

        val position2 = Position(
            pieces = listOf(whitePawn, blackPawn),
            sideToMove = Piece.Color.white,
            legalCastlings = LegalCastlings(),
            clock = Clock(),
        )

        assertFalse(position2.enPassantIsPossible)
    }

    @Test
    fun sideToMove() {
        var position = Position.standard
        assertEquals(Piece.Color.white, position.sideToMove)

        position = position.movePieceAt(Square.e2, Square.e4).first
        assertEquals(Piece.Color.black, position.sideToMove)

        position = position.movePieceAt(Square.e7, Square.e5).first
        assertEquals(Piece.Color.white, position.sideToMove)
    }

    @Test
    fun moveNonexistentPieces() {
        val position = Position.standard

        assertNull(position.movePieceAt(Square.a3, Square.a4).second)
        assertNull(position.move(Piece(Piece.Kind.pawn, Piece.Color.white, Square.a3), Square.a4).second)
    }
}
