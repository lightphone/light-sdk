package dev.neoneon.chesskit

/**
 * Parses and converts the Forsyth-Edwards Notation (FEN) of a chess position.
 *
 * For example, the standard starting position is represented as:
 * ```
 * "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
 * ```
 */
object FENParser {

    /**
     * Number of components in FEN
     * 1. Piece placement
     * 2. Side to move
     * 3. Castling ability
     * 4. En Passant
     * 5. Halfmoves
     * 6. Fullmoves
     */
    private const val COMPONENT_COUNT = 6

    /**
     * Parses a FEN string and returns a position.
     *
     * @param fen The FEN string of a chess position.
     * @return A representation of the chess position, or `null` if the FEN is invalid.
     */
    fun parse(fen: String): Position? {
        val sections = fen.split(Regex("\\s+"))

        if (sections.size != COMPONENT_COUNT) return null

        // piece placement

        val pieces = sections[0].split("/").flatMapIndexed { index, rankString ->
            val rank = Square.Rank(Square.Rank.range.last - index)
            var fileNumber = 0

            rankString.mapNotNull { c ->
                val digit = c.digitToIntOrNull()
                if (digit != null) {
                    fileNumber += digit
                    null
                } else {
                    fileNumber += 1
                    val file = Square.File(fileNumber)
                    val square = Square(file, rank)
                    Piece("$c", square)
                }
            }
        }

        // side to move

        val sideToMove = Piece.Color.fromRawValue(sections[1]) ?: Piece.Color.white

        // castling ability

        val legalCastlings = mutableListOf<Castling>()
        val castlingAbility = sections[2].toList().map { it.toString() }

        if (castlingAbility.contains("k")) legalCastlings.add(Castling.bK)
        if (castlingAbility.contains("K")) legalCastlings.add(Castling.wK)
        if (castlingAbility.contains("q")) legalCastlings.add(Castling.bQ)
        if (castlingAbility.contains("Q")) legalCastlings.add(Castling.wQ)

        // en passant target square

        var enPassant: EnPassant? = null

        val ep = sections[3]

        if (ep != "-" && ep.length == 2) {
            val epFile = Square.File.entries.firstOrNull { it.symbol.toString() == ep[0].toString() }
            val epRank = ep[1].toString().toIntOrNull()

            if (epRank == 3 && epFile != null) {
                enPassant = EnPassant(Piece(Piece.Kind.pawn, Piece.Color.white, Square(epFile, Square.Rank(epRank + 1))))
            } else if (epRank == 6 && epFile != null) {
                enPassant = EnPassant(Piece(Piece.Kind.pawn, Piece.Color.black, Square(epFile, Square.Rank(epRank - 1))))
            }
        }

        // clock

        val halfmove = sections[4].toIntOrNull()
        val fullmove = sections[5].toIntOrNull()

        val clock = if (halfmove != null && fullmove != null) Clock(halfmove, fullmove) else Clock()

        // final position

        return Position(
            pieces = pieces,
            sideToMove = sideToMove,
            legalCastlings = LegalCastlings(legal = legalCastlings),
            enPassant = enPassant,
            clock = clock,
        )
    }

    /**
     * Converts a [Position] object into a FEN string.
     *
     * @param position The chess position to convert.
     * @return A string containing the FEN of [position].
     */
    fun convert(position: Position): String {
        val fen = StringBuilder()

        // piece position

        for (r in Square.Rank.range.reversed()) {
            val rank = Square.Rank(r)
            var emptySpaceCounter = 0

            for (file in Square.File.entries) {
                val piece = position.piece(at = Square(file, rank))

                if (piece != null) {
                    if (emptySpaceCounter > 0) {
                        fen.append(emptySpaceCounter)
                    }

                    fen.append(piece.fen)
                    emptySpaceCounter = 0
                } else {
                    emptySpaceCounter += 1
                }
            }

            if (emptySpaceCounter > 0) {
                fen.append(emptySpaceCounter)
            }

            fen.append("/")
        }

        // remove extra `/`
        fen.setLength(fen.length - 1)

        fen.append(" ")

        // side to move

        fen.append(position.sideToMove.rawValue).append(" ")

        // castling ability

        fen.append(position.legalCastlings.fen).append(" ")

        // en passant

        val enPassant = position.enPassant
        if (enPassant != null) {
            fen.append(enPassant.captureSquare.notation).append(" ")
        } else {
            fen.append("- ")
        }

        // clock

        fen.append(position.clock.halfmoves).append(" ").append(position.clock.fullmoves)

        return fen.toString()
    }
}
