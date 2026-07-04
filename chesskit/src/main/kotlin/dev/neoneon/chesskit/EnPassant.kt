package dev.neoneon.chesskit

/** Structure that captures en passant moves. */
internal data class EnPassant(
    /** Pawn that is capable of being captured by en passant. */
    val pawn: Piece,
) {

    /** The square that the capturing pawn will move to after the en passant. */
    val captureSquare: Square
        get() = Square(pawn.square.file, Square.Rank(if (pawn.color == Piece.Color.white) 3 else 6))

    /**
     * Determines whether or not the pawn could be captured by en passant.
     *
     * [capturingPiece] must be an opposite color pawn that is on the same rank as the
     * target pawn and exactly 1 file away from the target pawn for this method to
     * return `true`, otherwise `false` is returned.
     *
     * Note: this function only considers the properties of the capturing piece and
     * [pawn], other validations may be required such as whether or not the side with
     * [capturingPiece] has passed their opportunity to capture by en passant.
     */
    fun couldBeCaptured(capturingPiece: Piece): Boolean =
        capturingPiece.kind == Piece.Kind.pawn &&
            capturingPiece.color == pawn.color.opposite &&
            capturingPiece.square.rank == pawn.square.rank &&
            kotlin.math.abs(capturingPiece.square.file.number - pawn.square.file.number) == 1
}
