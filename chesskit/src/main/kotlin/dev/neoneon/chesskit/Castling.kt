package dev.neoneon.chesskit

/** Structure that captures legal castling moves. */
internal data class LegalCastlings(
    private val legal: List<Castling> = listOf(Castling.bK, Castling.wK, Castling.bQ, Castling.wQ),
) {

    /**
     * Removes any castling moves associated with [piece] from the list of legal castlings.
     *
     * For example, if a king has moved, pass the king piece to this method to remove
     * any castlings associated with that king.
     *
     * @return An updated [LegalCastlings] with the relevant castlings invalidated.
     */
    fun invalidateCastling(piece: Piece): LegalCastlings = when (piece.kind) {
        Piece.Kind.king -> copy(legal = legal.filterNot { it.color == piece.color })
        Piece.Kind.rook -> copy(legal = legal.filterNot { it.color == piece.color && it.rookStart == piece.square })
        else -> this
    }

    /** Checks if a given castling is currently legal. */
    fun contains(castling: Castling): Boolean = legal.contains(castling)

    /**
     * The FEN representation of the legal castlings.
     *
     * Examples: `KQkq`, `QK`, `Qkq`, `k`, `-`
     */
    val fen: String get() = if (legal.isEmpty()) "-" else legal.map { it.fen }.sorted().joinToString("")
}

/**
 * Represents a castling move in a standard chess game.
 *
 * Contains various characteristics of the castling move such as king and rook
 * start and end squares and notation.
 */
data class Castling internal constructor(
    /** The side of the board for which this castling object represents. */
    internal val side: Side,
    /** The color of the king and rook castling. */
    internal val color: Piece.Color,
) {

    /** Represents the side of the board from which castling can occur. Either [king] or [queen]. */
    internal enum class Side {
        king, queen;

        val notation: String
            get() = when (this) {
                king -> "O-O"
                queen -> "O-O-O"
            }
    }

    /** The squares that the king will pass through when castling. */
    internal val squares: List<Square>
        get() = when (color) {
            Piece.Color.white -> if (side == Side.queen) listOf(Square.c1, Square.d1) else listOf(Square.f1, Square.g1)
            Piece.Color.black -> if (side == Side.queen) listOf(Square.c8, Square.d8) else listOf(Square.f8, Square.g8)
        }

    /** The squares between the king and rook that must be clear for castling. */
    internal val path: List<Square>
        get() = when (color) {
            Piece.Color.white -> if (side == Side.queen) listOf(Square.b1, Square.c1, Square.d1) else listOf(Square.f1, Square.g1)
            Piece.Color.black -> if (side == Side.queen) listOf(Square.b8, Square.c8, Square.d8) else listOf(Square.f8, Square.g8)
        }

    /** The starting square of the king, depending on the color. */
    val kingStart: Square
        get() = when (color) {
            Piece.Color.white -> Square.e1
            Piece.Color.black -> Square.e8
        }

    /** The ending square of the king, depending on the castle side and color. */
    val kingEnd: Square
        get() = when (color) {
            Piece.Color.white -> if (side == Side.queen) Square.c1 else Square.g1
            Piece.Color.black -> if (side == Side.queen) Square.c8 else Square.g8
        }

    /** The starting square of the rook, depending on the castle side and color. */
    val rookStart: Square
        get() = when (color) {
            Piece.Color.white -> if (side == Side.queen) Square.a1 else Square.h1
            Piece.Color.black -> if (side == Side.queen) Square.a8 else Square.h8
        }

    /** The ending square of the rook, depending on the castle side and color. */
    val rookEnd: Square
        get() = when (color) {
            Piece.Color.white -> if (side == Side.queen) Square.d1 else Square.f1
            Piece.Color.black -> if (side == Side.queen) Square.d8 else Square.f8
        }

    /**
     * The FEN representation of the castling.
     *
     * Possible values: `K`, `Q`, `k`, or `q`
     */
    internal val fen: String
        get() = when (color) {
            Piece.Color.white -> if (side == Side.queen) "Q" else "K"
            Piece.Color.black -> if (side == Side.queen) "q" else "k"
        }

    companion object {
        /** Kingside castle for black. */
        internal val bK = Castling(Side.king, Piece.Color.black)

        /** Kingside castle for white. */
        internal val wK = Castling(Side.king, Piece.Color.white)

        /** Queenside castle for black. */
        internal val bQ = Castling(Side.queen, Piece.Color.black)

        /** Queenside castle for white. */
        internal val wQ = Castling(Side.queen, Piece.Color.white)

        /** Initializes a [Castling] from its engine LAN representation, or `null` if invalid. */
        internal fun fromEngineLan(engineLan: String): Castling? = when (engineLan) {
            "e1g1" -> wK
            "e1c1" -> wQ
            "e8g8" -> bK
            "e8c8" -> bQ
            else -> null
        }
    }
}
