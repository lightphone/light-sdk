package dev.neoneon.chesskit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SANParserTests {

    @Test
    fun castling() {
        val p1 = Position("r3k3/8/8/8/8/8/8/4K2R w Kq - 0 1")!!
        val shortCastle = SANParser.parse("O-O", p1)
        assertEquals(Move.Result.castle(Castling.wK), shortCastle?.result)

        val p2 = Position("r3k3/8/8/8/8/8/8/5RK1 b q - 0 1")!!
        val longCastle = SANParser.parse("O-O-O", p2)
        assertEquals(Move.Result.castle(Castling.bQ), longCastle?.result)
    }

    @Test
    fun enPassant() {
        val p = Position("rnbqkbnr/pp2pppp/8/2pP4/8/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 1")!!
        val enPassant = SANParser.parse("dxc6", p)
        assertEquals(Move.Result.capture(Piece(Piece.Kind.pawn, Piece.Color.black, Square.c5)), enPassant?.result)
    }

    @Test
    fun promotion() {
        val p = Position("8/P7/8/8/8/8/8/8 w - - 0 1")!!
        val promotion = SANParser.parse("a8=Q", p)

        val promotedPiece = Piece(Piece.Kind.queen, Piece.Color.white, Square.a8)
        assertEquals(promotedPiece, promotion?.promotedPiece)
    }

    @Test
    fun checksAndMates() {
        val p1 = Position("8/k7/7Q/6R1/8/8/8/8 w - - 0 1")!!

        val check = SANParser.parse("Rg7+", p1)
        assertEquals(Move.CheckState.check, check?.checkState)

        val p2 = Position("8/k5R1/7Q/8/8/8/8/8 b - - 0 1")!!

        val kingMove = SANParser.parse("Ka8", p2)
        assertEquals(Move.CheckState.none, kingMove?.checkState)

        val p3 = Position("k7/6R1/7Q/8/8/8/8/8 w - - 0 1")!!

        val checkmate = SANParser.parse("Qh8#", p3)
        assertEquals(Move.CheckState.checkmate, checkmate?.checkState)
    }

    @Test
    fun disambiguation() {
        val pw = Position("3r3r/8/8/R7/4Q2Q/8/8/R6Q w - - 0 1")!!
        val pb = Position("3r3r/8/8/R7/4Q2Q/8/8/R6Q b - - 0 1")!!
        val pbCheck = Position("r4rk1/pp3pbp/1qp3p1/2B5/2BP2b1/Q1n2N2/P4PPP/3RK2R b K - 1 16")!!

        val rookFileMove = SANParser.parse("R1a3", pw)
        assertEquals(Move.Result.move, rookFileMove?.result)
        assertEquals(Piece.Kind.rook, rookFileMove?.piece?.kind)
        assertEquals(Move.Disambiguation.byRank(Square.Rank(1)), rookFileMove?.disambiguation)
        assertEquals(Square.a1, rookFileMove?.start)
        assertEquals(Square.a3, rookFileMove?.end)
        assertNull(rookFileMove?.promotedPiece)
        assertEquals(Move.CheckState.none, rookFileMove?.checkState)

        val rookRankMove = SANParser.parse("Rdf8", pb)
        assertEquals(Move.Result.move, rookRankMove?.result)
        assertEquals(Piece.Kind.rook, rookRankMove?.piece?.kind)
        assertEquals(Move.Disambiguation.byFile(Square.File.d), rookRankMove?.disambiguation)
        assertEquals(Square.d8, rookRankMove?.start)
        assertEquals(Square.f8, rookRankMove?.end)
        assertNull(rookRankMove?.promotedPiece)
        assertEquals(Move.CheckState.none, rookRankMove?.checkState)

        val rookCheckMove = SANParser.parse("Rfe8+", pbCheck)
        assertEquals(Move.Result.move, rookCheckMove?.result)
        assertEquals(Piece.Kind.rook, rookCheckMove?.piece?.kind)
        assertEquals(Move.Disambiguation.byFile(Square.File.f), rookCheckMove?.disambiguation)
        assertEquals(Square.f8, rookCheckMove?.start)
        assertEquals(Square.e8, rookCheckMove?.end)
        assertNull(rookCheckMove?.promotedPiece)
        assertEquals(Move.CheckState.check, rookCheckMove?.checkState)

        val queenMove = SANParser.parse("Qh4e1", pw)
        assertEquals(Move.Result.move, queenMove?.result)
        assertEquals(Piece.Kind.queen, queenMove?.piece?.kind)
        assertEquals(Move.Disambiguation.bySquare(Square.h4), queenMove?.disambiguation)
        assertEquals(Square.h4, queenMove?.start)
        assertEquals(Square.e1, queenMove?.end)
        assertNull(queenMove?.promotedPiece)
        assertEquals(Move.CheckState.none, queenMove?.checkState)
    }

    @Test
    fun testValidSANButInvalidMove() {
        assertNull(SANParser.parse("axb5", Position.standard))
        assertNull(SANParser.parse("Bb5", Position.standard))
    }

    @Test
    fun invalidSAN() {
        assertNull(SANParser.parse("bad move", Position.standard))
        assertNull(SANParser.parse("exf3", Position.standard))
        assertNull(SANParser.parse("aNf3", Position.standard))
        assertNull(SANParser.parse("e44", Position.standard))
    }
}
