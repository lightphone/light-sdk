package dev.neoneon.chesskit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoveTests {

    @Test
    fun moveSANInit() {
        val move = Move(Move.Result.move, Piece(Piece.Kind.pawn, Piece.Color.white, Square.e4), Square.e2, Square.e4)
        val moveFromSAN = Move("e4", Position.standard)

        assertEquals(move, moveFromSAN)
    }

    @Test
    fun moveInvalidSANInit() {
        assertNull(Move("e5", Position.standard))
    }

    @Test
    fun moveNotation() {
        val pawnD3 = Move(Move.Result.move, Piece(Piece.Kind.pawn, Piece.Color.white, Square.d3), Square.d2, Square.d3)
        assertEquals("d3", pawnD3.toString())
        assertEquals("d3", pawnD3.san)
        assertEquals("d2d3", pawnD3.lan)

        val bishopF4 = Move(Move.Result.move, Piece(Piece.Kind.bishop, Piece.Color.white, Square.f4), Square.c1, Square.f4)
        assertEquals("Bf4", bishopF4.toString())
        assertEquals("Bf4", bishopF4.san)
        assertEquals("c1f4", bishopF4.lan)
    }

    @Test
    fun captureNotation() {
        val capturedPiece = Piece(Piece.Kind.bishop, Piece.Color.black, Square.d5)
        val capturingPiece = Piece(Piece.Kind.pawn, Piece.Color.white, Square.e4)
        val capture = Move(Move.Result.capture(capturedPiece), capturingPiece, Square.e4, Square.d5)
        assertEquals("exd5", capture.san)
        assertEquals("e4d5", capture.lan)
    }

    @Test
    fun enPassantNotation() {
        val ep = EnPassant(Piece(Piece.Kind.pawn, Piece.Color.black, Square.d5))
        val move = Move(Move.Result.capture(ep.pawn), Piece(Piece.Kind.pawn, Piece.Color.white, Square.e5), Square.e5, Square.d6)
        assertEquals("exd6", move.san)
        assertEquals("e5d6", move.lan)
    }

    @Test
    fun castlingNotation() {
        val shortCastle = Move(Move.Result.castle(Castling.bK), Piece(Piece.Kind.king, Piece.Color.black, Square.e8), Square.e8, Square.g8)
        assertEquals("O-O", shortCastle.san)
        assertEquals("e8g8", shortCastle.lan)

        val longCastle = Move(
            Move.Result.castle(Castling.bQ),
            Piece(Piece.Kind.king, Piece.Color.black, Square.e8),
            Square.e8,
            Square.c8,
            checkState = Move.CheckState.checkmate,
        )
        assertEquals("O-O-O#", longCastle.san)
        assertEquals("e8c8", longCastle.lan)
    }

    @Test
    fun promotionsNotation() {
        val pawn = Piece(Piece.Kind.pawn, Piece.Color.white, Square.e8)
        val queen = Piece(Piece.Kind.queen, Piece.Color.white, Square.e8)
        val rook = Piece(Piece.Kind.rook, Piece.Color.white, Square.e8)

        val queenPromo = Move(Move.Result.move, pawn, Square.e7, Square.e8).copy(promotedPiece = queen)
        assertEquals("e8=Q", queenPromo.san)
        assertEquals("e7e8q", queenPromo.lan)

        val capturedPiece = Piece(Piece.Kind.bishop, Piece.Color.black, Square.f8)
        val rookCapturePromo = Move(
            Move.Result.capture(capturedPiece),
            pawn,
            Square.e7,
            Square.f8,
            checkState = Move.CheckState.check,
        ).copy(promotedPiece = rook)
        assertEquals("exf8=R+", rookCapturePromo.san)
        assertEquals("e7f8r", rookCapturePromo.lan)
    }

    @Test
    fun checksNotation() {
        val check = Move(
            Move.Result.move,
            Piece(Piece.Kind.queen, Piece.Color.white, Square.d4),
            Square.e3,
            Square.d4,
            checkState = Move.CheckState.check,
        )
        assertEquals("Qd4+", check.san)
        assertEquals("e3d4", check.lan)
    }

    @Test
    fun checkmateNotation() {
        val checkmate = Move(
            Move.Result.move,
            Piece(Piece.Kind.rook, Piece.Color.white, Square.g7),
            Square.g4,
            Square.g7,
            checkState = Move.CheckState.checkmate,
        )
        assertEquals("Rg7#", checkmate.san)
        assertEquals("g4g7", checkmate.lan)
    }

    @Test
    fun moveAssessments() {
        assertEquals("", Move.Assessment.`null`.notation)
        assertEquals("!", Move.Assessment.good.notation)
        assertEquals("?", Move.Assessment.mistake.notation)
        assertEquals("!!", Move.Assessment.brilliant.notation)
        assertEquals("??", Move.Assessment.blunder.notation)
        assertEquals("!?", Move.Assessment.interesting.notation)
        assertEquals("?!", Move.Assessment.dubious.notation)
        assertEquals("□", Move.Assessment.forced.notation)
        assertEquals("", Move.Assessment.singular.notation)
        assertEquals("", Move.Assessment.worst.notation)

        assertEquals(Move.Assessment.`null`, Move.Assessment(""))
        assertEquals(Move.Assessment.good, Move.Assessment("!"))
        assertEquals(Move.Assessment.mistake, Move.Assessment("?"))
        assertEquals(Move.Assessment.brilliant, Move.Assessment("!!"))
        assertEquals(Move.Assessment.blunder, Move.Assessment("??"))
        assertEquals(Move.Assessment.interesting, Move.Assessment("!?"))
        assertEquals(Move.Assessment.dubious, Move.Assessment("?!"))
        assertEquals(Move.Assessment.forced, Move.Assessment("□"))
    }
}
