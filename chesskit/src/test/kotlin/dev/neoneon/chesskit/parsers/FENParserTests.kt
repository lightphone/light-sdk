package dev.neoneon.chesskit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FENParserTests {

    @Test
    fun standardStartingPosition() {
        val p = Position.standard

        assertEquals(32, p.pieces.size)
        assertEquals(Piece.Color.white, p.sideToMove)
        assertEquals(LegalCastlings(listOf(Castling.bK, Castling.wK, Castling.bQ, Castling.wQ)), p.legalCastlings)
        assertNull(p.enPassant)
        assertEquals(0, p.clock.halfmoves)
        assertEquals(1, p.clock.fullmoves)
    }

    @Test
    fun complexPiecePlacement() {
        val p = Position.complex

        assertEquals(24, p.pieces.size)
        assertEquals(Piece.Color.black, p.sideToMove)
        assertEquals(LegalCastlings(listOf(Castling.bK, Castling.bQ)), p.legalCastlings)
        assertNull(p.enPassant)
        assertEquals(0, p.clock.halfmoves)
        assertEquals(20, p.clock.fullmoves)

        // pieces
        assertTrue(p.pieces.contains(Piece(Piece.Kind.rook, Piece.Color.black, Square.a8)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.bishop, Piece.Color.black, Square.c8)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.king, Piece.Color.black, Square.e8)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.knight, Piece.Color.black, Square.g8)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.rook, Piece.Color.black, Square.h8)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.pawn, Piece.Color.black, Square.a7)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.pawn, Piece.Color.black, Square.d7)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.pawn, Piece.Color.black, Square.f7)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.knight, Piece.Color.white, Square.g7)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.pawn, Piece.Color.black, Square.h7)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.knight, Piece.Color.black, Square.a6)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.bishop, Piece.Color.white, Square.d6)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.pawn, Piece.Color.black, Square.b5)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.knight, Piece.Color.white, Square.d5)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.pawn, Piece.Color.white, Square.e5)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.pawn, Piece.Color.white, Square.h5)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.pawn, Piece.Color.white, Square.g4)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.pawn, Piece.Color.white, Square.d3)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.queen, Piece.Color.white, Square.f3)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.pawn, Piece.Color.white, Square.a2)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.pawn, Piece.Color.white, Square.c2)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.king, Piece.Color.white, Square.e2)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.queen, Piece.Color.black, Square.a1)))
        assertTrue(p.pieces.contains(Piece(Piece.Kind.bishop, Piece.Color.black, Square.g1)))
    }

    @Test
    fun enPassantPosition() {
        val whiteEP = Position("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")!!

        assertEquals(Piece.Color.black, whiteEP.sideToMove)
        assertEquals(EnPassant(Piece(Piece.Kind.pawn, Piece.Color.white, Square.e4)), whiteEP.enPassant)

        val blackEP = Position("rnbqkbnr/pppppppp/8/4P3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2")!!

        assertEquals(Piece.Color.white, blackEP.sideToMove)
        assertEquals(EnPassant(Piece(Piece.Kind.pawn, Piece.Color.black, Square.e5)), blackEP.enPassant)
    }

    @Test
    fun invalidFen() {
        val p = Position("invalid")
        assertNull(p)

        val invalidSideToMove = Position("8/8/8/4p1K1/2k1P3/8/8/8 B - - 0 1")!!
        assertEquals(Piece.Color.white, invalidSideToMove.sideToMove)
    }

    @Test
    fun convertPosition() {
        val standardFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertEquals(standardFen, Position.standard.fen)

        val complexFen = "r1b1k1nr/p2p1pNp/n2B4/1p1NP2P/6P1/3P1Q2/P1P1K3/q5b1 b kq - 0 20"
        assertEquals(complexFen, Position.complex.fen)

        val epFen = "rnbqkbnr/ppppp1pp/8/8/4Pp2/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        assertEquals(epFen, Position.ep.fen)

        val castlingFen = "4k2r/6r1/8/8/8/8/3R4/R3K3 w Qk - 0 1"
        assertEquals(castlingFen, Position.castlingSample.fen)

        val fiftyMoveFen = "8/5k2/3p4/1p1Pp2p/pP2Pp1P/P4P1K/8/8 b - - 99 50"
        assertEquals(fiftyMoveFen, Position.fiftyMove.fen)
    }
}
