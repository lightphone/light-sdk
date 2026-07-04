package dev.neoneon.chesskit

/**
 * Represents a chess game.
 *
 * This object is the entry point for interacting with a full chess game within
 * ChessKit. It provides methods for making moves and exposes the played moves.
 *
 * @param startingWith The starting position of the game. Defaults to the standard starting position.
 * @param tags The PGN tags associated with this game.
 */
class Game(startingWith: Position = Position.standard, tags: Tags? = null) {

    /** The move tree representing all moves made in the game. */
    var moves: MoveTree = MoveTree()
        private set

    /** The move tree index of the starting position in the game. */
    var startingIndex: MoveTree.Index
        private set

    private val _positions: MutableMap<MoveTree.Index, Position> = mutableMapOf()

    /** A map of every position in the game, keyed by move index. */
    val positions: Map<MoveTree.Index, Position> get() = _positions

    /** Contains the tag pairs for this game. */
    var tags: Tags = tags ?: Tags()

    /** The starting position of the game. */
    val startingPosition: Position? get() = _positions[startingIndex]

    init {
        val index = if (startingWith.sideToMove == Piece.Color.white) MoveTree.Index.minimum else MoveTree.Index.minimum.next
        startingIndex = index
        _positions[index] = startingWith
        moves.minimumIndex = index
    }

    // MARK: Moves

    /**
     * Perform the provided move in the game.
     *
     * @param move The move to perform.
     * @param from The current move index to make the move from.
     * @return The move index of the resulting position. If the move couldn't be made,
     * the provided [from] index is returned directly.
     *
     * This method does not make any move legality assumptions, it will attempt to make
     * the move defined by [move] by moving pieces at the provided starting/ending
     * squares and making any necessary captures, promotions, etc. It is the
     * responsibility of the caller to ensure the move is legal, see [Board].
     *
     * If [move] is the same as the upcoming move in the current variation of [from],
     * the move is not made, otherwise another variation with the same first move as
     * the existing one would be created.
     */
    fun make(move: Move, from: MoveTree.Index): MoveTree.Index {
        val existingMoveIndex = moves.nextIndex(containing = move, forIndex = from)
        if (existingMoveIndex != null) return existingMoveIndex

        val newIndex = moves.add(move = move, toParentIndex = from)
        val currentPosition = _positions[from] ?: return from

        var newPosition = currentPosition

        when (val result = move.result) {
            is Move.Result.move -> {
                newPosition = newPosition.movePieceAt(move.start, move.end).first
                if (move.piece.kind == Piece.Kind.pawn) newPosition = newPosition.resetHalfmoveClock()
            }

            is Move.Result.capture -> {
                newPosition = newPosition.remove(result.piece)
                newPosition = newPosition.movePieceAt(move.start, move.end).first
                newPosition = newPosition.resetHalfmoveClock()
            }

            is Move.Result.castle -> {
                newPosition = newPosition.castle(result.castling).first
            }
        }

        val promotedPiece = move.promotedPiece
        if (promotedPiece != null) {
            newPosition = newPosition.promote(move.end, promotedPiece.kind)
        }

        _positions[newIndex] = newPosition
        return newIndex
    }

    /**
     * Perform the provided move in the game.
     *
     * @param move The SAN string of the move to perform.
     * @param from The current move index to make the move from.
     * @return The move index of the resulting position. If the move couldn't be made,
     * the provided [from] index is returned directly.
     *
     * This method does not make any move legality assumptions, it will attempt to make
     * the move defined by [move] by moving pieces at the provided starting/ending
     * squares and making any necessary captures, promotions, etc. It is the
     * responsibility of the caller to ensure the move is legal, see [Board].
     */
    fun make(move: String, from: MoveTree.Index): MoveTree.Index {
        val position = _positions[from] ?: return from
        val parsedMove = SANParser.parse(move, position) ?: return from
        return make(parsedMove, from)
    }

    /**
     * Perform the provided moves in the game.
     *
     * @param moves A list of SAN strings of the moves to perform.
     * @param from The current move index to make the moves from.
     * @return The move index of the resulting position. If the moves couldn't be made,
     * the provided [from] index is returned directly.
     *
     * This method does not make any move legality assumptions, it will attempt to make
     * the moves defined by [moves] by moving pieces at the provided starting/ending
     * squares and making any necessary captures, promotions, etc. It is the
     * responsibility of the caller to ensure the moves are legal, see [Board].
     */
    fun make(moves: List<String>, from: MoveTree.Index): MoveTree.Index {
        var index = from

        for (moveString in moves) {
            index = make(moveString, index)
        }

        return index
    }

    /**
     * Annotates the move at the provided [moveAt] index.
     */
    fun annotate(moveAt: MoveTree.Index, assessment: Move.Assessment = Move.Assessment.`null`, comment: String = "") {
        moves.annotate(moveAt = moveAt, assessment = assessment, comment = comment)
    }

    /**
     * Annotates the position at the provided [positionAt] index.
     */
    fun annotate(positionAt: MoveTree.Index, assessment: Position.Assessment) {
        moves.annotate(positionAt = positionAt, assessment = assessment)
        _positions[positionAt]?.assessment = assessment
    }

    /** The PGN representation of the game. */
    val pgn: String get() = PGNParser.convert(this)

    override fun toString(): String = pgn

    override fun equals(other: Any?): Boolean {
        if (other !is Game) return false
        return moves == other.moves && startingIndex == other.startingIndex && positions == other.positions && tags == other.tags
    }

    override fun hashCode(): Int = java.util.Objects.hash(moves, startingIndex, positions, tags)

    // MARK: - Tags

    /** Represents a PGN tag pair. */
    data class Tag(
        /** The name of the tag pair. Used as the key in a PGN tag pair. */
        val name: String,
        /**
         * The value of the tag pair.
         *
         * Appears at the top of the PGN after the corresponding [name], within brackets.
         */
        val wrappedValue: String = "",
    ) {
        /** The PGN representation of this tag. Formatted as `[Name "Value"]`. */
        val pgn: String get() = if (wrappedValue.isEmpty()) "" else "[$name \"$wrappedValue\"]"
    }

    /** Contains the PGN tag pairs for a game. */
    data class Tags(
        /** Name of the tournament or match event. Example: `"F/S Return Match"`. */
        var event: String = "",
        /**
         * Location of the event. Example: `"Belgrade, Serbia JUG"`.
         *
         * The format for this value is "City, Region COUNTRY", where "COUNTRY" is the
         * three-letter International Olympic Committee code for the country.
         *
         * Although not part of the specification, some online chess platforms will
         * include a URL or website as the site value.
         */
        var site: String = "",
        /**
         * Starting date of the game, in YYYY.MM.DD format. Example: `"1992.11.04"`.
         *
         * `"??"` is used for unknown values.
         */
        var date: String = "",
        /** Playing round ordinal of the game within the event. Example `"29"`. */
        var round: String = "",
        /**
         * Player of the white pieces, in "Lastname, Firstname" format.
         * Example: `"Fischer, Robert J."`.
         */
        var white: String = "",
        /**
         * Player of the black pieces, in "Lastname, Firstname" format.
         * Example: `"Spassky, Boris V."`.
         */
        var black: String = "",
        /**
         * Result of the game. Example: `"1/2-1/2"`.
         *
         * It is recorded as White score, dash, then Black score, or `*` (other, e.g.,
         * the game is ongoing).
         */
        var result: String = "",
        /** The person providing notes to the game. (optional) */
        var annotator: String = "",
        /** String value denoting the total number of half-moves played. (optional) */
        var plyCount: String = "",
        /** e.g. 40/7200:3600 (moves per seconds: sudden death seconds) (optional) */
        var timeControl: String = "",
        /** Time the game started, in HH:MM:SS format, in local clock time. (optional) */
        var time: String = "",
        /**
         * Gives more details about the termination of the game. It may be abandoned,
         * adjudication (result determined by third-party adjudication), death,
         * emergency, normal, rules infraction, time forfeit, or unterminated. (optional)
         */
        var termination: String = "",
        /** The mode of play used for the game. (optional) `"OTB"` or `"ICS"`. */
        var mode: String = "",
        /**
         * The initial position of the chessboard, in Forsyth-Edwards Notation. (optional)
         *
         * This is used to record partial games (starting at some initial position). It
         * is also necessary for chess variants such as Chess960, where the initial
         * position is not always the same as traditional chess.
         *
         * If a FEN tag is used, a separate tag pair SetUp must also appear and have its
         * value set to 1.
         */
        var fen: String = "",
        var setUp: String = "",
        /** Extra custom tags. The key will be used as the tag name in the PGN. */
        var other: Map<String, String> = mapOf(),
    ) {

        /**
         * Whether or not all the standard mandatory tags for PGN archival are set.
         *
         * These include `event`, `site`, `date`, `round`, `white`, `black`, and
         * `result` (known as the "Seven Tag Roster").
         */
        val isValid: Boolean
            get() = listOf(event, site, date, round, white, black, result).all { it.isNotEmpty() }

        /**
         * Returns all named tags.
         *
         * Does not include custom tags included in [other].
         */
        val all: List<Tag>
            get() = listOf(
                Tag("Event", event),
                Tag("Site", site),
                Tag("Date", date),
                Tag("Round", round),
                Tag("White", white),
                Tag("Black", black),
                Tag("Result", result),
                Tag("Annotator", annotator),
                Tag("PlyCount", plyCount),
                Tag("TimeControl", timeControl),
                Tag("Time", time),
                Tag("Termination", termination),
                Tag("Mode", mode),
                Tag("FEN", fen),
                Tag("SetUp", setUp),
            )
    }
}

/** Initialize a game with a PGN string. Throws [PGNParserException] if the PGN is invalid. */
fun Game(pgn: String): Game = PGNParser.parse(pgn)
