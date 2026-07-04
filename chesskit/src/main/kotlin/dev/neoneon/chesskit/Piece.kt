package dev.neoneon.chesskit

/** Represents a piece on the chess board. */
data class Piece(
    /** The kind of piece, e.g. [Kind.pawn]. */
    val kind: Kind,
    /** The color of the piece. */
    val color: Color,
    /** The square where this piece is located on the board. */
    val square: Square,
) {

    /** Represents the color of a piece. */
    enum class Color(val rawValue: String) {
        black("b"), white("w");

        /** The opposite color of the given color. */
        val opposite: Color get() = if (this == black) white else black

        override fun toString(): String = when (this) {
            white -> "White"
            black -> "Black"
        }

        companion object {
            fun fromRawValue(rawValue: String): Color? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /** Represents the type of piece. */
    enum class Kind(val rawValue: String) {
        pawn(""),
        knight("N"), bishop("B"), rook("R"), queen("Q"), king("K");

        /** The notation of the given piece kind. */
        val notation: String get() = rawValue

        override fun toString(): String = when (this) {
            pawn -> "Pawn"
            bishop -> "Bishop"
            knight -> "Knight"
            rook -> "Rook"
            queen -> "Queen"
            king -> "King"
        }

        companion object {
            fun fromRawValue(rawValue: String): Kind? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    companion object {
        /**
         * Initializes a chess piece from its FEN notation.
         *
         * @param fen The Forsyth-Edwards Notation of a piece kind and color, e.g. `"p"`.
         * @param square The square the piece is located on, e.g. `.a1`.
         */
        operator fun invoke(fen: String, square: Square): Piece? = when (fen) {
            "p" -> Piece(Kind.pawn, Color.black, square)
            "b" -> Piece(Kind.bishop, Color.black, square)
            "n" -> Piece(Kind.knight, Color.black, square)
            "r" -> Piece(Kind.rook, Color.black, square)
            "q" -> Piece(Kind.queen, Color.black, square)
            "k" -> Piece(Kind.king, Color.black, square)
            "P" -> Piece(Kind.pawn, Color.white, square)
            "B" -> Piece(Kind.bishop, Color.white, square)
            "N" -> Piece(Kind.knight, Color.white, square)
            "R" -> Piece(Kind.rook, Color.white, square)
            "Q" -> Piece(Kind.queen, Color.white, square)
            "K" -> Piece(Kind.king, Color.white, square)
            else -> null
        }
    }

    /**
     * The FEN representation of the piece.
     *
     * Note: this value does not convey any information regarding
     * the piece's location on the board (only kind and color).
     */
    val fen: String
        get() = when (color to kind) {
            Color.black to Kind.pawn -> "p"
            Color.black to Kind.bishop -> "b"
            Color.black to Kind.knight -> "n"
            Color.black to Kind.rook -> "r"
            Color.black to Kind.queen -> "q"
            Color.black to Kind.king -> "k"
            Color.white to Kind.pawn -> "P"
            Color.white to Kind.bishop -> "B"
            Color.white to Kind.knight -> "N"
            Color.white to Kind.rook -> "R"
            Color.white to Kind.queen -> "Q"
            Color.white to Kind.king -> "K"
            else -> error("unreachable")
        }

    val graphic: String
        get() = when (color to kind) {
            Color.black to Kind.pawn -> "♟︎"
            Color.black to Kind.bishop -> "♝"
            Color.black to Kind.knight -> "♞"
            Color.black to Kind.rook -> "♜"
            Color.black to Kind.queen -> "♛"
            Color.black to Kind.king -> "♚"
            Color.white to Kind.pawn -> "♙"
            Color.white to Kind.bishop -> "♗"
            Color.white to Kind.knight -> "♘"
            Color.white to Kind.rook -> "♖"
            Color.white to Kind.queen -> "♕"
            Color.white to Kind.king -> "♔"
            else -> error("unreachable")
        }

    override fun toString(): String = "$color $kind on ${square.notation}"
}
