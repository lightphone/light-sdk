package dev.neoneon.chesskit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CastlingTests {

    @Test
    fun castling() {
        val board = Board(Position.castlingSample)
        assertTrue(board.position.legalCastlings.contains(Castling.bK))
        assertFalse(board.position.legalCastlings.contains(Castling.wK))
        assertFalse(board.position.legalCastlings.contains(Castling.bQ))
        assertTrue(board.position.legalCastlings.contains(Castling.wQ))

        // white queenside castle
        val wQmove = assertNotNull(board.move(Square.e1, Square.c1))
        assertEquals(Move.Result.castle(Castling.wQ), wQmove.result)

        // black kingside castle
        val bkMove = assertNotNull(board.move(Square.e8, Square.g8))
        assertEquals(Move.Result.castle(Castling.bK), bkMove.result)
    }

    @Test
    fun invalidCastling() {
        val position = Position(
            pieces = listOf(
                Piece(Piece.Kind.queen, Piece.Color.black, Square.e8),
                Piece(Piece.Kind.king, Piece.Color.white, Square.e1),
                Piece(Piece.Kind.rook, Piece.Color.white, Square.h1),
            ),
        )
        val board = Board(position)

        // attempt to castle while in check
        assertFalse(board.canMove(Square.e1, Square.g1))

        // attempt to castle through check
        board.move(Square.e8, Square.f8)
        assertFalse(board.canMove(Square.e1, Square.g1))

        // valid castling move
        board.move(Square.f8, Square.h8)
        assertTrue(board.canMove(Square.e1, Square.g1))
    }

    @Test
    fun invalidCastlingThroughPiece() {
        val position = Position(
            pieces = listOf(
                Piece(Piece.Kind.bishop, Piece.Color.white, Square.f1),
                Piece(Piece.Kind.king, Piece.Color.white, Square.e1),
                Piece(Piece.Kind.rook, Piece.Color.white, Square.h1),
            ),
        )
        val board = Board(position)

        // attempt to castle through another piece
        assertFalse(board.canMove(Square.e1, Square.g1))

        // valid castling move
        board.move(Square.f1, Square.c4)
        assertTrue(board.canMove(Square.e1, Square.g1))
    }
}
