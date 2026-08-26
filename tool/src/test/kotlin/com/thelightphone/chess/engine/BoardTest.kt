package com.thelightphone.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardTest {
    @Test
    fun startPositionMoveCounts() {
        val board = Board.start()
        assertEquals(20, board.legalMoveCount())
        assertEquals(20, Board.perft(board, 1))
        assertEquals(400, Board.perft(board, 2))
        assertEquals(8902, Board.perft(board, 3))
    }

    @Test
    fun kiwipetePerft() {
        val board = Board()
        board.loadFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48, Board.perft(board, 1))
        assertEquals(2039, Board.perft(board, 2))
    }

    @Test
    fun enPassantIsLegal() {
        val board = Board.start()
        board.applyUci("e2e4")
        board.applyUci("d7d5")
        board.applyUci("e4e5")
        board.applyUci("f7f5")
        val moves = board.legalMoves().map { it.uci() }
        assertTrue("e5f6" in moves)
        board.applyUci("e5f6")
        assertEquals(0, board.squares[parseSquare("f5")])
        assertTrue(board.squares[parseSquare("f6")] != 0)
    }

    @Test
    fun whiteCastling() {
        val board = Board()
        board.loadFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val uci = board.legalMoves().map { it.uci() }
        assertTrue("e1g1" in uci)
        assertTrue("e1c1" in uci)
        board.applyUci("e1g1")
        assertEquals(KING or WHITE, board.squares[G1])
        assertEquals(ROOK or WHITE, board.squares[F1])
    }

    @Test
    fun foolsMate() {
        val board = Board.start()
        board.applyUci("f2f3")
        board.applyUci("e7e5")
        board.applyUci("g2g4")
        board.applyUci("d8h4")
        assertTrue(board.isCheckmate())
        assertEquals(GameResult.LOSS, board.gameResult(playerIsWhite = true))
    }

    @Test
    fun fenRoundTrip() {
        val fen = "rnbqkb1r/pppp1ppp/5n2/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 4 3"
        val board = Board()
        board.loadFen(fen)
        assertEquals(fen, board.toFen())
    }

    @Test
    fun makeUnmakeRestoresFen() {
        val board = Board.start()
        val before = board.toFen()
        val moves = IntArray(256)
        val n = board.generateLegalMoves(moves)
        board.makeMove(moves[0])
        board.unmakeMove(moves[0])
        assertEquals(before, board.toFen())
        assertEquals(n, board.generateLegalMoves(moves))
    }

    @Test
    fun engineReturnsLegalMove() {
        val board = Board.start()
        val legal = board.legalMoves().map { it.encoded }.toSet()
        for (level in BotLevel.entries) {
            val move = Search.pickMove(board, level, remainingMs = null, rng = kotlin.random.Random(1))
            assertTrue(move in legal, "level $level played illegal $move")
            assertFalse(board.isCheckmate())
        }
        assertEquals(Board.START_FEN, board.toFen())
    }
}
