package dev.neoneon.chesskit

/** Represents a move on a chess board. */
data class Move(
    /** The result of the move. */
    val result: Result,
    /**
     * The piece that made the move.
     *
     * Warning: Do not refer to this piece's `square` directly, use the move's
     * [start] and [end] properties as needed.
     */
    val piece: Piece,
    /** The starting square of the move. */
    val start: Square,
    /** The ending square of the move. */
    val end: Square,
    /** The check state resulting from the move. */
    val checkState: CheckState = CheckState.none,
    /** The move assessment annotation. */
    var assessment: Assessment = Assessment.`null`,
    /** The comment associated with a move. */
    var comment: String = "",
    /** The piece that was promoted to, if applicable. */
    val promotedPiece: Piece? = null,
    /** The move disambiguation, if applicable. */
    val disambiguation: Disambiguation? = null,
) {

    /** The result of the move. */
    sealed interface Result {
        data object move : Result
        data class capture(val piece: Piece) : Result
        data class castle(val castling: Castling) : Result
    }

    /** The check state resulting from the move. */
    enum class CheckState {
        none, check, checkmate, stalemate;

        internal val notation: String
            get() = when (this) {
                none, stalemate -> ""
                check -> "+"
                checkmate -> "#"
            }
    }

    /** Rank, file, or square disambiguation of moves. */
    sealed interface Disambiguation {
        data class byFile(val file: Square.File) : Disambiguation
        data class byRank(val rank: Square.Rank) : Disambiguation
        data class bySquare(val square: Square) : Disambiguation
    }

    /** The SAN representation of the move. */
    val san: String get() = SANParser.convert(this)

    /**
     * The engine LAN representation of the move.
     *
     * Note: this is intended for engine communication so piece names,
     * capture/check indicators, etc. are not included.
     */
    val lan: String get() = EngineLANParser.convert(this)

    /**
     * Single move assessments.
     *
     * The raw String value corresponds to what is displayed in a PGN string.
     */
    enum class Assessment(val rawValue: String) {
        `null`("\$0"),
        good("\$1"),
        mistake("\$2"),
        brilliant("\$3"),
        blunder("\$4"),
        interesting("\$5"),
        dubious("\$6"),
        forced("\$7"),
        singular("\$8"),
        worst("\$9"),
        ;

        /** The human-readable move assessment notation. */
        val notation: String
            get() = when (this) {
                `null` -> ""
                good -> "!"
                mistake -> "?"
                brilliant -> "!!"
                blunder -> "??"
                interesting -> "!?"
                dubious -> "?!"
                forced -> "□"
                singular -> ""
                worst -> ""
            }

        companion object {
            fun fromRawValue(rawValue: String): Assessment? = entries.firstOrNull { it.rawValue == rawValue }

            /** Initialize an [Assessment] from its human-readable notation (e.g. `"!"`, `"?!"`). */
            operator fun invoke(notation: String): Assessment? = when (notation) {
                "" -> `null`
                "!" -> good
                "?" -> mistake
                "!!" -> brilliant
                "??" -> blunder
                "!?" -> interesting
                "?!" -> dubious
                "□" -> forced
                else -> null
            }
        }
    }

    override fun toString(): String = san
}

/** Initialize a move with a given SAN string. Returns `null` if the provided SAN string is invalid. */
fun Move(san: String, position: Position): Move? = SANParser.parse(san, position)
