package dev.neoneon.chesskit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpecialMoveTests {

    @Test
    fun legalCastlingInvalidationForKings() {
        val blackKing = Piece(Piece.Kind.king, Piece.Color.black, Square.e8)
        val whiteKing = Piece(Piece.Kind.king, Piece.Color.white, Square.e1)

        var legalCastlings = LegalCastlings()
        legalCastlings = legalCastlings.invalidateCastling(blackKing)
        assertFalse(legalCastlings.contains(Castling.bK))
        assertFalse(legalCastlings.contains(Castling.bQ))
        assertTrue(legalCastlings.contains(Castling.wK))
        assertTrue(legalCastlings.contains(Castling.wQ))

        legalCastlings = legalCastlings.invalidateCastling(whiteKing)
        assertFalse(legalCastlings.contains(Castling.bK))
        assertFalse(legalCastlings.contains(Castling.bQ))
        assertFalse(legalCastlings.contains(Castling.wK))
        assertFalse(legalCastlings.contains(Castling.wQ))
    }

    @Test
    fun legalCastlingInvalidationForRooks() {
        val blackKingsideRook = Piece(Piece.Kind.rook, Piece.Color.black, Square.h8)
        val blackQueensideRook = Piece(Piece.Kind.rook, Piece.Color.black, Square.a8)
        val whiteKingsideRook = Piece(Piece.Kind.rook, Piece.Color.white, Square.h1)
        val whiteQueensideRook = Piece(Piece.Kind.rook, Piece.Color.white, Square.a1)

        var legalCastlings = LegalCastlings()
        legalCastlings = legalCastlings.invalidateCastling(blackKingsideRook)
        assertFalse(legalCastlings.contains(Castling.bK))
        assertTrue(legalCastlings.contains(Castling.bQ))
        assertTrue(legalCastlings.contains(Castling.wK))
        assertTrue(legalCastlings.contains(Castling.wQ))

        legalCastlings = legalCastlings.invalidateCastling(blackQueensideRook)
        assertFalse(legalCastlings.contains(Castling.bK))
        assertFalse(legalCastlings.contains(Castling.bQ))
        assertTrue(legalCastlings.contains(Castling.wK))
        assertTrue(legalCastlings.contains(Castling.wQ))

        legalCastlings = legalCastlings.invalidateCastling(whiteKingsideRook)
        assertFalse(legalCastlings.contains(Castling.bK))
        assertFalse(legalCastlings.contains(Castling.bQ))
        assertFalse(legalCastlings.contains(Castling.wK))
        assertTrue(legalCastlings.contains(Castling.wQ))

        legalCastlings = legalCastlings.invalidateCastling(whiteQueensideRook)
        assertFalse(legalCastlings.contains(Castling.bK))
        assertFalse(legalCastlings.contains(Castling.bQ))
        assertFalse(legalCastlings.contains(Castling.wK))
        assertFalse(legalCastlings.contains(Castling.wQ))
    }

    @Test
    fun enPassantCaptureSquare() {
        val blackPawn = Piece(Piece.Kind.pawn, Piece.Color.black, Square.d5)
        val blackEnPassant = EnPassant(blackPawn)
        assertEquals(Square.d6, blackEnPassant.captureSquare)
        assertTrue(blackEnPassant.couldBeCaptured(Piece(Piece.Kind.pawn, Piece.Color.white, Square.e5)))
        assertTrue(blackEnPassant.couldBeCaptured(Piece(Piece.Kind.pawn, Piece.Color.white, Square.c5)))
        assertFalse(blackEnPassant.couldBeCaptured(Piece(Piece.Kind.pawn, Piece.Color.black, Square.e5)))
        assertFalse(blackEnPassant.couldBeCaptured(Piece(Piece.Kind.pawn, Piece.Color.white, Square.f5)))
        assertFalse(blackEnPassant.couldBeCaptured(Piece(Piece.Kind.pawn, Piece.Color.white, Square.b5)))
        assertFalse(blackEnPassant.couldBeCaptured(Piece(Piece.Kind.bishop, Piece.Color.white, Square.c5)))

        val whitePawn = Piece(Piece.Kind.pawn, Piece.Color.white, Square.d4)
        val whiteEnPassant = EnPassant(whitePawn)
        assertEquals(Square.d3, whiteEnPassant.captureSquare)
        assertTrue(whiteEnPassant.couldBeCaptured(Piece(Piece.Kind.pawn, Piece.Color.black, Square.e4)))
        assertTrue(whiteEnPassant.couldBeCaptured(Piece(Piece.Kind.pawn, Piece.Color.black, Square.c4)))
        assertFalse(whiteEnPassant.couldBeCaptured(Piece(Piece.Kind.pawn, Piece.Color.white, Square.e4)))
        assertFalse(whiteEnPassant.couldBeCaptured(Piece(Piece.Kind.pawn, Piece.Color.black, Square.f4)))
        assertFalse(whiteEnPassant.couldBeCaptured(Piece(Piece.Kind.pawn, Piece.Color.black, Square.b4)))
        assertFalse(whiteEnPassant.couldBeCaptured(Piece(Piece.Kind.bishop, Piece.Color.black, Square.c4)))
    }
}
