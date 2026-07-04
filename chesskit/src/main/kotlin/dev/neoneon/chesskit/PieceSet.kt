package dev.neoneon.chesskit

/**
 * Stores the bitboards for all pieces.
 *
 * Also contains convenient amalgamations of different combinations of pieces.
 *
 * This is an immutable value type: methods that "mutate" the set (e.g. [add], [remove],
 * [move], [replace]) return a new [PieceSet] rather than modifying the receiver in place.
 *
 * Note: fields use a `b`/`w` prefix (rather than case, as in the original ChessKit
 * bitboard field naming) since the JVM's case-insensitive-by-default getter naming
 * (`getK()` for both `k` and `K`) would otherwise collide.
 */
internal data class PieceSet(
    /** Bitboard for black king pieces. */
    val bK: Bitboard = 0uL,
    /** Bitboard for black queen pieces. */
    val bQ: Bitboard = 0uL,
    /** Bitboard for black rook pieces. */
    val bR: Bitboard = 0uL,
    /** Bitboard for black bishop pieces. */
    val bB: Bitboard = 0uL,
    /** Bitboard for black knight pieces. */
    val bN: Bitboard = 0uL,
    /** Bitboard for black pawn pieces. */
    val bP: Bitboard = 0uL,
    /** Bitboard for white king pieces. */
    val wK: Bitboard = 0uL,
    /** Bitboard for white queen pieces. */
    val wQ: Bitboard = 0uL,
    /** Bitboard for white rook pieces. */
    val wR: Bitboard = 0uL,
    /** Bitboard for white bishop pieces. */
    val wB: Bitboard = 0uL,
    /** Bitboard for white knight pieces. */
    val wN: Bitboard = 0uL,
    /** Bitboard for white pawn pieces. */
    val wP: Bitboard = 0uL,
) {

    /** Bitboard for all the pieces. */
    val all: Bitboard get() = black or white
    /** Bitboard for all the black pieces. */
    val black: Bitboard get() = bK or bQ or bR or bB or bN or bP
    /** Bitboard for all the white pieces. */
    val white: Bitboard get() = wK or wQ or wR or wB or wN or wP

    /** Bitboard for all the king pieces. */
    val kings: Bitboard get() = bK or wK
    /** Bitboard for all the queen pieces. */
    val queens: Bitboard get() = bQ or wQ
    /** Bitboard for all the rook pieces. */
    val rooks: Bitboard get() = bR or wR
    /** Bitboard for all the bishop pieces. */
    val bishops: Bitboard get() = bB or wB
    /** Bitboard for all the knight pieces. */
    val knights: Bitboard get() = bN or wN
    /** Bitboard for all the pawn pieces. */
    val pawns: Bitboard get() = bP or wP

    /** Bitboard for all the diagonal sliding pieces. */
    val diagonals: Bitboard get() = wQ or bQ or wB or bB
    /** Bitboard for all the vertical/horizontal sliding pieces. */
    val lines: Bitboard get() = wQ or bQ or wR or bR

    val pieces: List<Piece>
        get() = bK.squares.map { Piece(Piece.Kind.king, Piece.Color.black, it) } +
            bQ.squares.map { Piece(Piece.Kind.queen, Piece.Color.black, it) } +
            bR.squares.map { Piece(Piece.Kind.rook, Piece.Color.black, it) } +
            bB.squares.map { Piece(Piece.Kind.bishop, Piece.Color.black, it) } +
            bN.squares.map { Piece(Piece.Kind.knight, Piece.Color.black, it) } +
            bP.squares.map { Piece(Piece.Kind.pawn, Piece.Color.black, it) } +
            wK.squares.map { Piece(Piece.Kind.king, Piece.Color.white, it) } +
            wQ.squares.map { Piece(Piece.Kind.queen, Piece.Color.white, it) } +
            wR.squares.map { Piece(Piece.Kind.rook, Piece.Color.white, it) } +
            wB.squares.map { Piece(Piece.Kind.bishop, Piece.Color.white, it) } +
            wN.squares.map { Piece(Piece.Kind.knight, Piece.Color.white, it) } +
            wP.squares.map { Piece(Piece.Kind.pawn, Piece.Color.white, it) }

    fun get(color: Piece.Color): Bitboard = when (color) {
        Piece.Color.white -> white
        Piece.Color.black -> black
    }

    fun get(kind: Piece.Kind): Bitboard = when (kind) {
        Piece.Kind.pawn -> pawns
        Piece.Kind.knight -> knights
        Piece.Kind.bishop -> bishops
        Piece.Kind.rook -> rooks
        Piece.Kind.queen -> queens
        Piece.Kind.king -> kings
    }

    fun get(square: Square): Piece? {
        val sq = square.bb
        return when {
            bK and sq != 0uL -> Piece(Piece.Kind.king, Piece.Color.black, square)
            bQ and sq != 0uL -> Piece(Piece.Kind.queen, Piece.Color.black, square)
            bR and sq != 0uL -> Piece(Piece.Kind.rook, Piece.Color.black, square)
            bB and sq != 0uL -> Piece(Piece.Kind.bishop, Piece.Color.black, square)
            bN and sq != 0uL -> Piece(Piece.Kind.knight, Piece.Color.black, square)
            bP and sq != 0uL -> Piece(Piece.Kind.pawn, Piece.Color.black, square)
            wK and sq != 0uL -> Piece(Piece.Kind.king, Piece.Color.white, square)
            wQ and sq != 0uL -> Piece(Piece.Kind.queen, Piece.Color.white, square)
            wR and sq != 0uL -> Piece(Piece.Kind.rook, Piece.Color.white, square)
            wB and sq != 0uL -> Piece(Piece.Kind.bishop, Piece.Color.white, square)
            wN and sq != 0uL -> Piece(Piece.Kind.knight, Piece.Color.white, square)
            wP and sq != 0uL -> Piece(Piece.Kind.pawn, Piece.Color.white, square)
            else -> null
        }
    }

    fun add(piece: Piece): PieceSet = add(piece, piece.square)

    fun add(piece: Piece, square: Square): PieceSet {
        val sq = square.bb
        return when (piece.color to piece.kind) {
            Piece.Color.black to Piece.Kind.king -> copy(bK = bK or sq)
            Piece.Color.black to Piece.Kind.queen -> copy(bQ = bQ or sq)
            Piece.Color.black to Piece.Kind.rook -> copy(bR = bR or sq)
            Piece.Color.black to Piece.Kind.bishop -> copy(bB = bB or sq)
            Piece.Color.black to Piece.Kind.knight -> copy(bN = bN or sq)
            Piece.Color.black to Piece.Kind.pawn -> copy(bP = bP or sq)
            Piece.Color.white to Piece.Kind.king -> copy(wK = wK or sq)
            Piece.Color.white to Piece.Kind.queen -> copy(wQ = wQ or sq)
            Piece.Color.white to Piece.Kind.rook -> copy(wR = wR or sq)
            Piece.Color.white to Piece.Kind.bishop -> copy(wB = wB or sq)
            Piece.Color.white to Piece.Kind.knight -> copy(wN = wN or sq)
            Piece.Color.white to Piece.Kind.pawn -> copy(wP = wP or sq)
            else -> error("unreachable")
        }
    }

    fun remove(piece: Piece): PieceSet {
        val sq = piece.square.bb.inv()
        return when (piece.color to piece.kind) {
            Piece.Color.black to Piece.Kind.king -> copy(bK = bK and sq)
            Piece.Color.black to Piece.Kind.queen -> copy(bQ = bQ and sq)
            Piece.Color.black to Piece.Kind.rook -> copy(bR = bR and sq)
            Piece.Color.black to Piece.Kind.bishop -> copy(bB = bB and sq)
            Piece.Color.black to Piece.Kind.knight -> copy(bN = bN and sq)
            Piece.Color.black to Piece.Kind.pawn -> copy(bP = bP and sq)
            Piece.Color.white to Piece.Kind.king -> copy(wK = wK and sq)
            Piece.Color.white to Piece.Kind.queen -> copy(wQ = wQ and sq)
            Piece.Color.white to Piece.Kind.rook -> copy(wR = wR and sq)
            Piece.Color.white to Piece.Kind.bishop -> copy(wB = wB and sq)
            Piece.Color.white to Piece.Kind.knight -> copy(wN = wN and sq)
            Piece.Color.white to Piece.Kind.pawn -> copy(wP = wP and sq)
            else -> error("unreachable")
        }
    }

    /** Replaces a piece's kind with another, such as when performing a piece promotion. */
    fun replace(kind: Piece.Kind, piece: Piece): PieceSet {
        val newPiece = piece.copy(kind = kind)
        return remove(piece).add(newPiece)
    }

    fun move(piece: Piece, square: Square): PieceSet = remove(piece).add(piece, square)

    override fun toString(): String {
        val s = StringBuilder()

        for (rank in Square.Rank.range.reversed()) {
            s.append("$rank")

            for (file in Square.File.entries) {
                val sq = Square(file, Square.Rank(rank))
                val piece = get(sq)

                if (piece != null) {
                    s.append(" ${if (ChessKitConfiguration.printOptions.mode == ChessKitConfiguration.PrintOptions.PrintMode.graphic) piece.graphic else piece.fen}")
                } else {
                    s.append(" ·")
                }
            }

            s.append("\n")
        }

        return s.toString() + "  a b c d e f g h"
    }

    companion object {
        fun of(pieces: List<Piece>): PieceSet = pieces.fold(PieceSet()) { set, piece -> set.add(piece) }
    }
}
