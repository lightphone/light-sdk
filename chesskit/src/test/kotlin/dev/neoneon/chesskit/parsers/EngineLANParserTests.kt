package dev.neoneon.chesskit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineLANParserTests {

    @Test
    fun capture() {
        val position = Position("8/8/8/4p3/3P4/8/8/8 w - - 0 1")!!
        val move = EngineLANParser.parse("d4e5", Piece.Color.white, position)

        val capturedPiece = Piece(Piece.Kind.pawn, Piece.Color.black, Square.e5)
        assertEquals(Move.Result.capture(capturedPiece), move?.result)
    }

    @Test
    fun castling() {
        val p1 = Position("8/8/8/8/8/8/8/4K2R w KQ - 0 1")!!
        val wShortCastle = EngineLANParser.parse("e1g1", Piece.Color.white, p1)
        assertEquals(Move.Result.castle(Castling.wK), wShortCastle?.result)

        val p2 = Position("8/8/8/8/8/8/8/R3K3 w KQ - 0 1")!!
        val wLongCastle = EngineLANParser.parse("e1c1", Piece.Color.white, p2)
        assertEquals(Move.Result.castle(Castling.wQ), wLongCastle?.result)

        val p3 = Position("4k2r/8/8/8/8/8/8/8 b kq - 0 1")!!
        val bShortCastle = EngineLANParser.parse("e8g8", Piece.Color.black, p3)
        assertEquals(Move.Result.castle(Castling.bK), bShortCastle?.result)

        val p4 = Position("r3k3/8/8/8/8/8/8/8 b kq - 0 1")!!
        val bLongCastle = EngineLANParser.parse("e8c8", Piece.Color.black, p4)
        assertEquals(Move.Result.castle(Castling.bQ), bLongCastle?.result)
    }

    @Test
    fun promotion() {
        val p = Position("8/P7/8/8/8/8/8/8 w - - 0 1")!!

        val qPromotion = EngineLANParser.parse("a7a8q", Piece.Color.white, p)
        val promotedQueen = Piece(Piece.Kind.queen, Piece.Color.white, Square.a8)
        assertEquals(promotedQueen, qPromotion?.promotedPiece)

        val rPromotion = EngineLANParser.parse("a7a8r", Piece.Color.white, p)
        val promotedRook = Piece(Piece.Kind.rook, Piece.Color.white, Square.a8)
        assertEquals(promotedRook, rPromotion?.promotedPiece)

        val bPromotion = EngineLANParser.parse("a7a8b", Piece.Color.white, p)
        val promotedBishop = Piece(Piece.Kind.bishop, Piece.Color.white, Square.a8)
        assertEquals(promotedBishop, bPromotion?.promotedPiece)

        val nPromotion = EngineLANParser.parse("a7a8n", Piece.Color.white, p)
        val promotedKnight = Piece(Piece.Kind.knight, Piece.Color.white, Square.a8)
        assertEquals(promotedKnight, nPromotion?.promotedPiece)
    }

    @Test
    fun validLANButInvalidMove() {
        assertNull(EngineLANParser.parse("a4b5", Piece.Color.white, Position.standard))
        assertNull(EngineLANParser.parse("f8b5", Piece.Color.black, Position.standard))
    }

    @Test
    fun invalidLAN() {
        assertNull(EngineLANParser.parse("bad move", Piece.Color.white, Position.standard))
    }
}
