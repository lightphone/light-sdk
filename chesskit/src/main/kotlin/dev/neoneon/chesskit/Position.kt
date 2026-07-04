package dev.neoneon.chesskit

/**
 * Represents the collection of pieces on the chess board.
 *
 * This is an immutable value type: internal "mutating" helper functions
 * (e.g. [move], [castle], [remove], [promote]) return a new [Position]
 * rather than modifying the receiver in place.
 */
data class Position internal constructor(
    /** Bitboard-based piece set used to manage piece positions. */
    internal val pieceSet: PieceSet,
    /** The side that is set to move next. */
    val sideToMove: Piece.Color,
    /**
     * Legal castlings based on position only (does not take into account checks, etc.)
     *
     * This only contains castlings that are legal based on whether or not
     * the king(s) and rook(s) have moved.
     */
    internal val legalCastlings: LegalCastlings,
    /**
     * Contains information about a pawn that can be captured by en passant.
     *
     * This property is set whenever a pawn moves by 2 squares.
     */
    internal val enPassant: EnPassant?,
    /** Indicates whether the en passant stored in [enPassant] is valid. */
    internal val enPassantIsPossible: Boolean,
    /** Keeps track of the number of moves in a game for the current position. */
    val clock: Clock,
    /** The position assessment annotation. */
    var assessment: Assessment,
) {

    /** The pieces currently existing on the board in this position. */
    val pieces: List<Piece> get() = pieceSet.pieces

    /** Initialize a position with a given list of pieces and characteristics. */
    internal constructor(
        pieces: List<Piece>,
        sideToMove: Piece.Color = Piece.Color.white,
        legalCastlings: LegalCastlings = LegalCastlings(),
        enPassant: EnPassant? = null,
        clock: Clock = Clock(),
        assessment: Assessment = Assessment.`null`,
    ) : this(PieceSet.of(pieces), sideToMove, legalCastlings, enPassant, enPassant != null, clock, assessment)

    /** Provides the chess piece located at the given square, or `null` if the square is empty. */
    fun piece(at: Square): Piece? = pieceSet.get(at)

    /**
     * Moves the given [piece] to the square [end].
     *
     * @return The resulting [Position] and the updated piece containing the final square
     * as its location, or `null` if the given piece was not found in this position.
     *
     * Warning: Do not use this function to perform castling moves. To castle a
     * king and rook, call [castle].
     */
    internal fun move(piece: Piece, end: Square, updateClockAndSideToMove: Boolean = true): Pair<Position, Piece?> {
        if (pieceSet.get(piece.square) == null) return this to null

        val newLegalCastlings = legalCastlings.invalidateCastling(piece)
        val newPieceSet = pieceSet.move(piece, end)

        var newClock = clock
        var newSideToMove = sideToMove

        if (updateClockAndSideToMove) {
            newClock = newClock.copy(
                halfmoves = newClock.halfmoves + 1,
                fullmoves = if (piece.color == Piece.Color.black) newClock.fullmoves + 1 else newClock.fullmoves,
            )
            newSideToMove = newSideToMove.opposite
        }

        val newPosition = copy(
            pieceSet = newPieceSet,
            sideToMove = newSideToMove,
            legalCastlings = newLegalCastlings,
            clock = newClock,
        )

        return newPosition to newPieceSet.get(end)
    }

    /**
     * Performs the given [castling].
     *
     * @return The resulting [Position] and the updated king piece containing the final
     * square as its location, or `null` if the king was not found in this position.
     *
     * This function assumes castling is valid for the provided [castling]. If the king move is
     * valid, it will be performed whether or not there is actually a piece on the rook start square.
     *
     * Note: the rook will only be moved if the king move succeeds.
     */
    internal fun castle(castling: Castling): Pair<Position, Piece?> {
        val (afterKingMove, kingMove) = movePieceAt(castling.kingStart, castling.kingEnd)

        return if (kingMove != null) {
            val (afterRookMove, _) = afterKingMove.movePieceAt(castling.rookStart, castling.rookEnd, updateClockAndSideToMove = false)
            afterRookMove to kingMove
        } else {
            afterKingMove to null
        }
    }

    /**
     * Moves a piece from [start] to [end].
     *
     * @return The resulting [Position] and the updated piece containing the final square
     * as its location, or `null` if no piece was found at [start].
     */
    internal fun movePieceAt(start: Square, end: Square, updateClockAndSideToMove: Boolean = true): Pair<Position, Piece?> {
        val piece = pieceSet.get(start) ?: return this to null
        return move(piece, end, updateClockAndSideToMove)
    }

    /** Removes the given [piece] from the position, if present. */
    internal fun remove(piece: Piece): Position = copy(pieceSet = pieceSet.remove(piece))

    /**
     * Promotes a pawn at the given [square] to the given piece [kind].
     *
     * If a piece is not found at [square], this method has no effect. This method
     * contains no logic to determine if the piece can be legally promoted, and such
     * checks should be done before calling this method.
     */
    internal fun promote(square: Square, kind: Piece.Kind): Position {
        val piece = pieceSet.get(square) ?: return this
        return copy(pieceSet = pieceSet.replace(kind, piece))
    }

    /**
     * Resets the halfmove counter in the [Clock].
     *
     * This should be used whenever a pawn is moved or a capture is made.
     */
    internal fun resetHalfmoveClock(): Position = copy(clock = clock.copy(halfmoves = 0))

    /** Indicates whether the current position has insufficient material. */
    val hasInsufficientMaterial: Boolean
        get() {
            val set = pieceSet
            val pawnsRooksQueens = set.pawns or set.rooks or set.queens

            return if (pawnsRooksQueens == 0uL) {
                if (set.all.toLong().countOneBits() <= 3) {
                    // 3 pieces in this scenario means two kings and either
                    // 1 bishop or 1 knight, i.e. insufficient material
                    true
                } else {
                    // check if no knights and all bishops
                    // are on the same color square, i.e. insufficient material
                    val allBLight = set.bishops and darkBb == 0uL // all bishops on light squares
                    val allBDark = set.bishops and lightBb == 0uL // all bishops on dark squares

                    set.knights == 0uL && (allBLight || allBDark)
                }
            } else {
                // not insufficient material if pawns, rooks, or queens are on the board
                false
            }
        }

    /** The FEN representation of the position. */
    val fen: String get() = FENParser.convert(this)

    /**
     * Returns a hash that only reflects properties relevant to detecting draw by
     * repetition: piece placement, side to move, castling rights, and en passant
     * possibility. Deliberately excludes [clock], [enPassant] (only its possibility
     * matters), and [assessment].
     */
    internal fun repetitionHash(): Int = java.util.Objects.hash(pieceSet, sideToMove, legalCastlings, enPassantIsPossible)

    // MARK: - Assessment

    /**
     * Single position assessments.
     *
     * The raw String value corresponds to what is displayed in a PGN string.
     */
    enum class Assessment(val rawValue: String) {
        `null`(""),
        drawishPosition("\$10"),
        equalChancesQuietPosition("\$11"),
        equalChancesActivePosition("\$12"),
        unclearPosition("\$13"),
        whiteHasSlightAdvantage("\$14"),
        blackHasSlightAdvantage("\$15"),
        whiteHasModerateAdvantage("\$16"),
        blackHasModerateAdvantage("\$17"),
        whiteHasDecisiveAdvantage("\$18"),
        blackHasDecisiveAdvantage("\$19"),
        whiteHasCrushingAdvantage("\$20"),
        blackHasCrushingAdvantage("\$21"),
        whiteInZugzwang("\$22"),
        blackInZugzwang("\$23"),
        whiteHasSlightSpaceAdvantage("\$24"),
        blackHasSlightSpaceAdvantage("\$25"),
        whiteHasModerateSpaceAdvantage("\$26"),
        blackHasModerateSpaceAdvantage("\$27"),
        whiteHasDecisiveSpaceAdvantage("\$28"),
        blackHasDecisiveSpaceAdvantage("\$29"),
        whiteHasSlightTimeAdvantage("\$30"),
        blackHasSlightTimeAdvantage("\$31"),
        whiteHasModerateTimeAdvantage("\$32"),
        blackHasModerateTimeAdvantage("\$33"),
        whiteHasDecisiveTimeAdvantage("\$34"),
        blackHasDecisiveTimeAdvantage("\$35"),
        whiteHasInitiative("\$36"),
        blackHasInitiative("\$37"),
        whiteHasLastingInitiative("\$38"),
        blackHasLastingInitiative("\$39"),
        whiteHasAttack("\$40"),
        blackHasAttack("\$41"),
        whiteInsufficientCompensation("\$42"),
        blackInsufficientCompensation("\$43"),
        whiteSufficientCompensation("\$44"),
        blackSufficientCompensation("\$45"),
        whiteMoreThanAdequateCompensation("\$46"),
        blackMoreThanAdequateCompensation("\$47"),
        whiteHasSlightCenterControlAdvantage("\$48"),
        blackHasSlightCenterControlAdvantage("\$49"),
        whiteHasModerateCenterControlAdvantage("\$50"),
        blackHasModerateCenterControlAdvantage("\$51"),
        whiteHasDecisiveCenterControlAdvantage("\$52"),
        blackHasDecisiveCenterControlAdvantage("\$53"),
        whiteHasSlightKingsideControlAdvantage("\$54"),
        blackHasSlightKingsideControlAdvantage("\$55"),
        whiteHasModerateKingsideControlAdvantage("\$56"),
        blackHasModerateKingsideControlAdvantage("\$57"),
        whiteHasDecisiveKingsideControlAdvantage("\$58"),
        blackHasDecisiveKingsideControlAdvantage("\$59"),
        whiteHasSlightQueensideControlAdvantage("\$60"),
        blackHasSlightQueensideControlAdvantage("\$61"),
        whiteHasModerateQueensideControlAdvantage("\$62"),
        blackHasModerateQueensideControlAdvantage("\$63"),
        whiteHasDecisiveQueensideControlAdvantage("\$64"),
        blackHasDecisiveQueensideControlAdvantage("\$65"),
        whiteVulnerableFirstRank("\$66"),
        blackVulnerableFirstRank("\$67"),
        whiteWellProtectedFirstRank("\$68"),
        blackWellProtectedFirstRank("\$69"),
        whitePoorlyProtectedKing("\$70"),
        blackPoorlyProtectedKing("\$71"),
        whiteWellProtectedKing("\$72"),
        blackWellProtectedKing("\$73"),
        whitePoorlyPlacedKing("\$74"),
        blackPoorlyPlacedKing("\$75"),
        whiteWellPlacedKing("\$76"),
        blackWellPlacedKing("\$77"),
        whiteVeryWeakPawnStructure("\$78"),
        blackVeryWeakPawnStructure("\$79"),
        whiteModeratelyWeakPawnStructure("\$80"),
        blackModeratelyWeakPawnStructure("\$81"),
        whiteModeratelyStrongPawnStructure("\$82"),
        blackModeratelyStrongPawnStructure("\$83"),
        whiteVeryStrongPawnStructure("\$84"),
        blackVeryStrongPawnStructure("\$85"),
        whitePoorKnightPlacement("\$86"),
        blackPoorKnightPlacement("\$87"),
        whiteGoodKnightPlacement("\$88"),
        blackGoodKnightPlacement("\$89"),
        whitePoorBishopPlacement("\$90"),
        blackPoorBishopPlacement("\$91"),
        whiteGoodBishopPlacement("\$92"),
        blackGoodBishopPlacement("\$93"),
        whitePoorRookPlacement("\$94"),
        blackPoorRookPlacement("\$95"),
        whiteGoodRookPlacement("\$96"),
        blackGoodRookPlacement("\$97"),
        whitePoorQueenPlacement("\$98"),
        blackPoorQueenPlacement("\$99"),
        whiteGoodQueenPlacement("\$100"),
        blackGoodQueenPlacement("\$101"),
        whitePoorPieceCoordination("\$102"),
        blackPoorPieceCoordination("\$103"),
        whiteGoodPieceCoordination("\$104"),
        blackGoodPieceCoordination("\$105"),
        whitePlayedOpeningVeryPoorly("\$106"),
        blackPlayedOpeningVeryPoorly("\$107"),
        whitePlayedOpeningPoorly("\$108"),
        blackPlayedOpeningPoorly("\$109"),
        whitePlayedOpeningWell("\$110"),
        blackPlayedOpeningWell("\$111"),
        whitePlayedOpeningVeryWell("\$112"),
        blackPlayedOpeningVeryWell("\$113"),
        whitePlayedMiddlegameVeryPoorly("\$114"),
        blackPlayedMiddlegameVeryPoorly("\$115"),
        whitePlayedMiddlegamePoorly("\$116"),
        blackPlayedMiddlegamePoorly("\$117"),
        whitePlayedMiddlegameWell("\$118"),
        blackPlayedMiddlegameWell("\$119"),
        whitePlayedMiddlegameVeryWell("\$120"),
        blackPlayedMiddlegameVeryWell("\$121"),
        whitePlayedEndingVeryPoorly("\$122"),
        blackPlayedEndingVeryPoorly("\$123"),
        whitePlayedEndingPoorly("\$124"),
        blackPlayedEndingPoorly("\$125"),
        whitePlayedEndingWell("\$126"),
        blackPlayedEndingWell("\$127"),
        whitePlayedEndingVeryWell("\$128"),
        blackPlayedEndingVeryWell("\$129"),
        whiteHasSlightCounterplay("\$130"),
        blackHasSlightCounterplay("\$131"),
        whiteHasModerateCounterplay("\$132"),
        blackHasModerateCounterplay("\$133"),
        whiteHasDecisiveCounterplay("\$134"),
        blackHasDecisiveCounterplay("\$135"),
        whiteModerateTimeControlPressure("\$136"),
        blackModerateTimeControlPressure("\$137"),
        whiteSevereTimeControlPressure("\$138"),
        blackSevereTimeControlPressure("\$139"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Assessment? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    companion object {
        /** A random chess position that can be used for testing. */
        val test = Position(
            pieces = listOf(
                Piece(Piece.Kind.pawn, Piece.Color.black, Square.c3),
                Piece(Piece.Kind.bishop, Piece.Color.black, Square.f4),
                Piece(Piece.Kind.rook, Piece.Color.black, Square.a6),
                Piece(Piece.Kind.knight, Piece.Color.black, Square.e6),
                Piece(Piece.Kind.king, Piece.Color.black, Square.h8),
                Piece(Piece.Kind.pawn, Piece.Color.white, Square.b2),
                Piece(Piece.Kind.queen, Piece.Color.white, Square.d5),
                Piece(Piece.Kind.king, Piece.Color.white, Square.g3),
            ),
        )

        /** The standard starting chess position. */
        val standard = FENParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")!!
    }

    override fun toString(): String = pieceSet.toString()
}

/**
 * Initialize a position with a provided FEN string. Returns `null` if the provided
 * FEN string is invalid.
 */
fun Position(fen: String): Position? = FENParser.parse(fen)
