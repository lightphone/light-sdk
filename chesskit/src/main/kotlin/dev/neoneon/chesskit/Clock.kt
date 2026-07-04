package dev.neoneon.chesskit

/** Tracks the number of moves in a game for the purposes of regulating the 50 move rule. */
data class Clock(
    /**
     * The number of halfmoves, incremented after each move.
     * It is reset to zero after each capture or pawn move.
     */
    val halfmoves: Int = 0,
    /** The number of fullmoves, incremented after each Black move. */
    val fullmoves: Int = 1,
) {
    companion object {
        /** The maximum number of half moves before a draw by the fifty move rule should be called. */
        const val HALF_MOVE_MAXIMUM = 100
    }
}
