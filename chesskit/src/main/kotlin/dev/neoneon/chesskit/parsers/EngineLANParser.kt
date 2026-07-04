package dev.neoneon.chesskit

/**
 * Parses and converts the Long Algebraic Notation (LAN) of a chess move used by chess engines.
 *
 * This notation omits the piece type and any indication for special move types such
 * as captures, castling, checks, etc.
 *
 * Examples:
 * - e2e4
 * - e7e5
 * - e1g1 (white short castling)
 * - e7e8q (for promotion)
 *
 * See the UCI protocol documentation for more information.
 */
object EngineLANParser {

    // MARK: Public

    /**
     * Parses a LAN string and returns a move.
     *
     * @param lan The (engine) LAN string of a move.
     * @param color The color of the piece being moved.
     * @param position The current chess position to make the move from.
     * @return A representation of a move, or `null` if the LAN is invalid.
     *
     * This parser does not look for checks or checkmates, i.e. the move's
     * `checkState` will always be [Move.CheckState.none].
     */
    fun parse(lan: String, color: Piece.Color, position: Position): Move? {
        if (!isValid(lan)) return null

        val startSquareString = lan.substring(0, 2)
        val start = Square(startSquareString)

        val endSquareString = lan.substring(2, 4)
        val end = Square(endSquareString)

        var promotedPiece: Piece? = null

        if (lan.length == 5) {
            val pieceString = lan.last().uppercase()
            val pieceKind = Piece.Kind.fromRawValue(pieceString)
            if (pieceKind != null) {
                promotedPiece = Piece(pieceKind, color, end)
            }
        }

        val board = Board(position)

        if (!board.canMove(pieceAt = start, to = end)) return null
        val piece = position.piece(at = start) ?: return null

        val capturedPiece = position.piece(at = end)
        val moveResult: Move.Result = if (capturedPiece != null) {
            Move.Result.capture(capturedPiece)
        } else {
            val castling = Castling.fromEngineLan(lan)
            if (castling != null) Move.Result.castle(castling) else Move.Result.move
        }

        return Move(
            result = moveResult,
            piece = piece,
            start = start,
            end = end,
            checkState = Move.CheckState.none,
            promotedPiece = promotedPiece,
        )
    }

    /**
     * Converts a [Move] object into an engine LAN string.
     *
     * @param move The chess move to convert.
     * @return A string containing the engine LAN of [move].
     */
    fun convert(move: Move): String =
        move.start.notation + move.end.notation + (move.promotedPiece?.fen?.lowercase() ?: "")

    // MARK: Private

    /** Returns whether the provided engine LAN is valid. */
    private fun isValid(lan: String): Boolean = Pattern.move.matches(lan)

    /** Contains useful regex patterns for engine LAN parsing. */
    private object Pattern {
        val move = Regex("^([a-h][1-8]){2}[qrbn]?$")
    }
}
