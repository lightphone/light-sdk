package dev.neoneon.chesskit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PieceTests {

    @Test
    fun notation() {
        val pawn = Piece.Kind.pawn
        assertEquals("", pawn.notation)
        assertEquals("Pawn", pawn.toString())

        val bishop = Piece.Kind.bishop
        assertEquals("B", bishop.notation)
        assertEquals("Bishop", bishop.toString())

        val knight = Piece.Kind.knight
        assertEquals("N", knight.notation)
        assertEquals("Knight", knight.toString())

        val rook = Piece.Kind.rook
        assertEquals("R", rook.notation)
        assertEquals("Rook", rook.toString())

        val queen = Piece.Kind.queen
        assertEquals("Q", queen.notation)
        assertEquals("Queen", queen.toString())

        val king = Piece.Kind.king
        assertEquals("K", king.notation)
        assertEquals("King", king.toString())
    }

    @Test
    fun pieceColor() {
        val white = Piece.Color.white
        assertEquals("w", white.rawValue)
        assertEquals(Piece.Color.black, white.opposite)
        assertEquals("White", white.toString())

        val black = Piece.Color.black
        assertEquals("b", black.rawValue)
        assertEquals(Piece.Color.white, black.opposite)
        assertEquals("Black", black.toString())
    }

    @Test
    fun fenRepresentation() {
        val sq = Square.a1

        val wP = Piece("P", sq)
        assertEquals(Piece.Color.white, wP?.color)
        assertEquals(Piece.Kind.pawn, wP?.kind)
        assertEquals(sq, wP?.square)

        val wB = Piece("B", sq)
        assertEquals(Piece.Color.white, wB?.color)
        assertEquals(Piece.Kind.bishop, wB?.kind)
        assertEquals(sq, wB?.square)

        val wN = Piece("N", sq)
        assertEquals(Piece.Color.white, wN?.color)
        assertEquals(Piece.Kind.knight, wN?.kind)
        assertEquals(sq, wN?.square)

        val wR = Piece("R", sq)
        assertEquals(Piece.Color.white, wR?.color)
        assertEquals(Piece.Kind.rook, wR?.kind)
        assertEquals(sq, wR?.square)

        val wQ = Piece("Q", sq)
        assertEquals(Piece.Color.white, wQ?.color)
        assertEquals(Piece.Kind.queen, wQ?.kind)
        assertEquals(sq, wQ?.square)

        val wK = Piece("K", sq)
        assertEquals(Piece.Color.white, wK?.color)
        assertEquals(Piece.Kind.king, wK?.kind)
        assertEquals(sq, wK?.square)

        val bP = Piece("p", sq)
        assertEquals(Piece.Color.black, bP?.color)
        assertEquals(Piece.Kind.pawn, bP?.kind)
        assertEquals(sq, bP?.square)

        val bB = Piece("b", sq)
        assertEquals(Piece.Color.black, bB?.color)
        assertEquals(Piece.Kind.bishop, bB?.kind)
        assertEquals(sq, bB?.square)

        val bN = Piece("n", sq)
        assertEquals(Piece.Color.black, bN?.color)
        assertEquals(Piece.Kind.knight, bN?.kind)
        assertEquals(sq, bN?.square)

        val bR = Piece("r", sq)
        assertEquals(Piece.Color.black, bR?.color)
        assertEquals(Piece.Kind.rook, bR?.kind)
        assertEquals(sq, bR?.square)

        val bQ = Piece("q", sq)
        assertEquals(Piece.Color.black, bQ?.color)
        assertEquals(Piece.Kind.queen, bQ?.kind)
        assertEquals(sq, bQ?.square)

        val bK = Piece("k", sq)
        assertEquals(Piece.Color.black, bK?.color)
        assertEquals(Piece.Kind.king, bK?.kind)
        assertEquals(sq, bK?.square)
    }

    @Test
    fun invalidFenRepresentation() {
        assertNull(Piece("invalid", Square.a1))
    }
}
