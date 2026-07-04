package dev.neoneon.chesskit

/** Manages the state of the chess board and validates legal moves and game rules. */
class Board(position: Position = Position.standard) {

    /**
     * The current position represented on the board.
     *
     * To manually change the position, use [update].
     */
    var position: Position = position
        private set

    /**
     * The state of the board, based on [position].
     *
     * This value communicates if the active position represents a check,
     * checkmate, draw, or piece promotion.
     */
    var state: State = State.active
        private set

    /**
     * Occurrence counts for all the positions that have appeared on this board, keyed
     * by the position's [Position.repetitionHash]. Used to determine draw by repetition.
     */
    private var positionHashCounts: MutableMap<Int, Int> = mutableMapOf()

    /** Convenience accessor for the pieces in [position]. */
    private val pieceSet: PieceSet get() = position.pieceSet

    init {
        updateState()
    }

    // MARK: Public

    /**
     * Manually set the board's position.
     *
     * @param resetPositionCounts Whether to reset identical position counts for the
     * purposes of identifying three-fold repetitions. The default value is `false`.
     *
     * This also updates the board's [state].
     *
     * Note: [Board] internally keeps track of identical position counts to monitor
     * for threefold repetition draws. Setting the same position multiple times may
     * trigger this draw state if [resetPositionCounts] is not set to `true`. The
     * recommended way to update the position is by making sequential moves using [move].
     */
    fun update(position: Position, resetPositionCounts: Boolean = false) {
        if (resetPositionCounts) {
            positionHashCounts = mutableMapOf()
        }

        this.position = position
        updateState()
    }

    /**
     * Moves the piece at a given square to a new square.
     *
     * @return The [Move] object representing the move or `null` if the move is invalid.
     *
     * If [pieceAt] doesn't contain a piece or [to] is not a valid legal move for the
     * piece at [pieceAt], `null` is returned.
     *
     * The return value can be ignored if the intention is only to perform the move but
     * not capture the details in any way. If the move is not legal, this method returns
     * without performing any actions.
     *
     * This method also handles all the side effects of a given move, for example:
     * - Moving the king in a castling move will also move the corresponding rook.
     * - Moving to capture a piece removes the captured piece from the board.
     *
     * After this method returns, check the [state] value to see if the state of the
     * board's [position] has changed in a meaningful way.
     */
    fun move(pieceAt: Square, to: Square): Move? {
        val start = pieceAt
        val end = to

        if (!canMove(pieceAt = start, to = end)) return null
        val piece = pieceSet.get(start) ?: return null

        // en passant

        val enPassant = position.enPassant
        if (piece.kind == Piece.Kind.pawn && enPassant != null && enPassant.pawn.color == piece.color.opposite && end == enPassant.captureSquare) {
            val afterRemove = position.remove(enPassant.pawn)
            val (afterMove, _) = afterRemove.move(piece, end)
            position = afterMove
            return process(Move(Move.Result.capture(enPassant.pawn), piece, start, end))
        } else {
            position = position.copy(enPassant = null, enPassantIsPossible = false)
        }

        // castling

        if (piece.kind == Piece.Kind.king) {
            for (side in Castling.Side.entries) {
                val castling = Castling(side, piece.color)

                if (canCastle(piece.color, castling, pieceSet) && end == castling.kingEnd) {
                    val (afterCastle, _) = position.castle(castling)
                    position = afterCastle
                    return process(Move(Move.Result.castle(castling), piece, start, end))
                }
            }
        }

        // captures & moves

        val endPiece = position.piece(at = end)

        return if (endPiece != null && endPiece.color == piece.color.opposite) {
            val move = disambiguate(Move(Move.Result.capture(endPiece), piece, start, end), pieceSet)

            val afterRemove = position.remove(endPiece)
            val (afterMove, _) = afterRemove.move(piece, end)
            position = afterMove.resetHalfmoveClock()

            process(move)
        } else {
            val previousSet = pieceSet
            val (afterMove, updatedPiece) = position.move(piece, end)
            if (updatedPiece == null) return null
            position = afterMove

            val move = disambiguate(Move(Move.Result.move, updatedPiece, start, end), previousSet)

            if (updatedPiece.kind == Piece.Kind.pawn) {
                position = position.resetHalfmoveClock()

                if (kotlin.math.abs(start.rank.value - end.rank.value) == 2) {
                    position = position.copy(enPassant = EnPassant(updatedPiece))
                    position = position.copy(enPassantIsPossible = enPassantIsValid)
                }
            }

            process(move)
        }
    }

    /**
     * Checks if a piece at a given square can be moved to a new square.
     *
     * @return Whether or not the move is valid.
     */
    fun canMove(pieceAt: Square, to: Square): Boolean {
        val piece = pieceSet.get(pieceAt) ?: return false
        return legalMoves(piece, pieceSet) and to.bb != 0uL
    }

    /**
     * Returns the possible legal moves for a piece at a given square.
     *
     * @return A list of squares containing legal moves or an empty list if there are
     * no legal moves or if there is no piece at [square].
     */
    fun legalMoves(forPieceAt: Square): List<Square> {
        val piece = pieceSet.get(forPieceAt) ?: return emptyList()
        return legalMoves(piece, pieceSet).squares
    }

    /**
     * Completes a pawn promotion move.
     *
     * @return The final move containing the promoted piece.
     *
     * Call this when a pawn reaches the opposite side of the board and a piece to
     * promote to is selected to complete the promotion move.
     */
    fun completePromotion(move: Move, kind: Piece.Kind): Move {
        val promotedPiece = Piece(kind, move.piece.color, move.end)
        val updatedMove = move.copy(promotedPiece = promotedPiece)

        position = position.promote(move.end, kind)
        return process(updatedMove)
    }

    // MARK: Move Processing

    /** Determines end game state and handles pawn promotion for provided [move]. */
    private fun process(move: Move): Move {
        val checkState = checkState(move.piece.color)
        val processedMove = move.copy(checkState = checkState)

        updateState(processedMove)
        return processedMove
    }

    /**
     * Updates the board's [state].
     *
     * @param move Set this if updating the state after a move has been made so the
     * appropriate piece color can be used for check determination.
     */
    private fun updateState(move: Move? = null) {
        val moveColor = move?.piece?.color ?: position.sideToMove

        // pawn promotion
        if (move != null) {
            if (move.piece.kind == Piece.Kind.pawn &&
                ((move.end.rank.value == 8 && move.piece.color == Piece.Color.white) || (move.end.rank.value == 1 && move.piece.color == Piece.Color.black))
            ) {
                if (move.promotedPiece == null) {
                    // prevent any more state changes until promotion is completed,
                    // as the board is in an "incomplete" state
                    state = State.promotion(move)
                    return
                }
            }
        } else {
            if (position.sideToMove == Piece.Color.black) {
                val pawnSquare = (pieceSet.bP and rank1Bb).squares.firstOrNull()
                if (pawnSquare != null) {
                    val pendingMove = Move(
                        Move.Result.move,
                        Piece(Piece.Kind.pawn, Piece.Color.black, pawnSquare),
                        pawnSquare.up,
                        pawnSquare,
                    )
                    state = State.promotion(pendingMove)
                    return
                }
            } else if (position.sideToMove == Piece.Color.white) {
                val pawnSquare = (pieceSet.wP and rank8Bb).squares.firstOrNull()
                if (pawnSquare != null) {
                    val pendingMove = Move(
                        Move.Result.move,
                        Piece(Piece.Kind.pawn, Piece.Color.white, pawnSquare),
                        pawnSquare.down,
                        pawnSquare,
                    )
                    state = State.promotion(pendingMove)
                    return
                }
            }
        }

        // draw by repetition
        val key = position.repetitionHash()
        positionHashCounts[key] = (positionHashCounts[key] ?: 0) + 1

        // board state update
        val checkState = checkState(moveColor)

        state = when {
            checkState == Move.CheckState.checkmate -> State.checkmate(moveColor.opposite)
            checkState == Move.CheckState.stalemate -> State.draw(State.DrawReason.stalemate)
            position.clock.halfmoves >= Clock.HALF_MOVE_MAXIMUM -> State.draw(State.DrawReason.fiftyMoves)
            position.hasInsufficientMaterial -> State.draw(State.DrawReason.insufficientMaterial)
            positionHashCounts[key] == 3 -> State.draw(State.DrawReason.repetition)
            checkState == Move.CheckState.check -> State.check(moveColor.opposite)
            else -> State.active
        }
    }

    /** Determines the current check state for the provided [color]. */
    private fun checkState(color: Piece.Color): Move.CheckState {
        val legalMovesForOpposite = pieceSet.get(color.opposite).squares.flatMap { legalMoves(forPieceAt = it) }

        return if (isKingInCheck(color.opposite, pieceSet)) {
            if (legalMovesForOpposite.isEmpty()) Move.CheckState.checkmate else Move.CheckState.check
        } else {
            if (legalMovesForOpposite.isEmpty()) Move.CheckState.stalemate else Move.CheckState.none
        }
    }

    /**
     * Disambiguates any moves in [set] as they relate to [move].
     *
     * For example, if two identical pieces can legally move to a given square, this
     * method determines whether to disambiguate them by starting file, rank, or square.
     */
    private fun disambiguate(move: Move, set: PieceSet): Move {
        val disambiguationCandidates = set.get(move.piece.color) and
            set.get(move.piece.kind) and
            (set.pawns or set.kings).inv() and
            move.start.bb.inv()

        val ambiguousPieces = disambiguationCandidates.squares
            .mapNotNull { set.get(it) }
            .filter { (legalMoves(it, set) and move.end.bb) != 0uL }

        return if (ambiguousPieces.isEmpty()) {
            move
        } else {
            val fileConflict = ambiguousPieces.any { it.square.file == move.start.file }
            val rankConflict = ambiguousPieces.any { it.square.rank == move.start.rank }

            val disambiguation = when {
                !fileConflict -> Move.Disambiguation.byFile(move.start.file)
                !rankConflict -> Move.Disambiguation.byRank(move.start.rank)
                else -> Move.Disambiguation.bySquare(move.start)
            }

            move.copy(disambiguation = disambiguation)
        }
    }

    // MARK: Move Validation

    /** Determines the legal moves for the given [piece] in [set]. */
    private fun legalMoves(piece: Piece, set: PieceSet): Bitboard {
        val attacks = when (piece.kind) {
            Piece.Kind.king -> kingMoves(piece.color, piece.square.bb, set)
            Piece.Kind.queen -> queenAttacks(piece.square, set.all)
            Piece.Kind.rook -> rookAttacks(piece.square, set.all)
            Piece.Kind.bishop -> bishopAttacks(piece.square, set.all)
            Piece.Kind.knight -> knightAttacks[piece.square.bb] ?: 0uL
            Piece.Kind.pawn -> pawnAttacks(piece.color, piece.square.bb, set)
        }

        val us = set.get(piece.color)
        val pseudoLegalMoves = attacks and us.inv()

        val legal = pseudoLegalMoves.squares.filter { validate(piece, it) }

        return legal.bb
    }

    /** Determines if a pseudo-legal move for [piece] to [square] is valid. */
    private fun validate(piece: Piece, square: Square): Boolean {
        var testSet = pieceSet.remove(piece)

        val movedPiece = piece.copy(square = square)
        testSet = testSet.add(movedPiece)

        val enPassant = position.enPassant
        if (enPassant != null && enPassant.couldBeCaptured(piece) && enPassant.captureSquare == square) {
            testSet = testSet.remove(enPassant.pawn)
        }

        return !isKingInCheck(piece.color, testSet)
    }

    /** Whether the [Position.enPassant] stored in [position] is valid. */
    private val enPassantIsValid: Boolean
        get() {
            val ep = position.enPassant ?: return false

            return listOf(ep.pawn.square.left, ep.pawn.square.right).any { square ->
                val piece = position.piece(at = square)
                piece != null && ep.couldBeCaptured(piece) && validate(piece, ep.captureSquare)
            }
        }

    /**
     * Determines the positions of pieces that attack a given square.
     *
     * @param sq A bitboard corresponding to the square of interest.
     * @param set The piece set for which to calculate attackers.
     * @return A bitboard with the locations of the pieces in [set] that attack [sq].
     */
    private fun attackers(sq: Bitboard, set: PieceSet): Bitboard {
        val square = Square(sq) ?: return 0uL

        return ((kingAttacks[sq] ?: 0uL) and set.kings) or
            (rookAttacks(square, set.all) and set.lines) or
            (bishopAttacks(square, set.all) and set.diagonals) or
            ((knightAttacks[sq] ?: 0uL) and set.knights) or
            (pawnCaptures(Piece.Color.white, sq) and set.bP) or
            (pawnCaptures(Piece.Color.black, sq) and set.wP)
    }

    /** Determines if the king of the given piece [color] is in check, given [set]. */
    private fun isKingInCheck(color: Piece.Color, set: PieceSet): Boolean {
        val us = set.get(color)
        val attacks = attackers(set.kings and us, set)

        return (attacks and us.inv()) != 0uL
    }

    // MARK: Piece Attacks

    /**
     * Non-capturing pawn moves.
     *
     * For the purposes of [Board], en-passant is considered a non-capturing move.
     */
    private fun pawnMoves(color: Piece.Color, sq: Bitboard, set: PieceSet): Bitboard {
        val movement: (Int) -> Bitboard
        val isOnStartingRank: Boolean

        when (color) {
            Piece.Color.white -> {
                movement = { n -> sq.north(n) }
                isOnStartingRank = (sq and rank1Bb.north()) != 0uL
            }
            Piece.Color.black -> {
                movement = { n -> sq.south(n) }
                isOnStartingRank = (sq and rank8Bb.south()) != 0uL
            }
        }

        // single pawn push
        val singleMove = movement(1)

        // double pawn push for starting move
        val hasSingleMove = (singleMove and set.all.inv()) != 0uL
        val extraMove = if (isOnStartingRank && hasSingleMove) movement(2) else 0uL

        // en passant move
        var enPassantMove = 0uL

        val enPassant = position.enPassant
        val square = Square(sq)
        if (enPassant != null && square != null) {
            val piece = set.get(square)
            if (piece != null && enPassant.couldBeCaptured(piece)) {
                enPassantMove = enPassant.captureSquare.bb
            }
        }

        return (singleMove or extraMove or enPassantMove) and set.all.inv()
    }

    /**
     * Capturing pawn moves.
     *
     * For the purposes of [Board], en-passant is not considered a capturing move.
     */
    private fun pawnCaptures(color: Piece.Color, sq: Bitboard): Bitboard = when (color) {
        Piece.Color.white -> sq.northWest() or sq.northEast()
        Piece.Color.black -> sq.southWest() or sq.southEast()
    }

    /** The complete set of pawn moves, including capturing and non-capturing moves. */
    private fun pawnAttacks(color: Piece.Color, sq: Bitboard, set: PieceSet): Bitboard =
        pawnMoves(color, sq, set) or (pawnCaptures(color, sq) and set.get(color.opposite))

    /** Cached knight attack bitboards by square. */
    private val knightAttacks: Map<Bitboard, Bitboard> get() = Attacks.knights

    /** Returns cached bishop attack bitboards by square and occupancy. */
    private fun bishopAttacks(square: Square, occupancy: Bitboard): Bitboard = Attacks.bishops.attacks(square, occupancy)

    /** Returns cached rook attack bitboards by square and occupancy. */
    private fun rookAttacks(square: Square, occupancy: Bitboard): Bitboard = Attacks.rooks.attacks(square, occupancy)

    /** Returns cached queen attack bitboards by square and occupancy. */
    private fun queenAttacks(square: Square, occupancy: Bitboard): Bitboard =
        rookAttacks(square, occupancy) or bishopAttacks(square, occupancy)

    /** Cached king attack bitboards by square. */
    private val kingAttacks: Map<Bitboard, Bitboard> get() = Attacks.kings

    /** King attacks from a given square plus castling moves. */
    private fun kingMoves(color: Piece.Color, sq: Bitboard, set: PieceSet): Bitboard {
        val castleMoves = mutableListOf<Square>()

        val kingSide = Castling(Castling.Side.king, color)
        if (canCastle(color, kingSide, set)) castleMoves.add(kingSide.kingEnd)

        val queenSide = Castling(Castling.Side.queen, color)
        if (canCastle(color, queenSide, set)) castleMoves.add(queenSide.kingEnd)

        return (kingAttacks[sq] ?: 0uL) + castleMoves.bb
    }

    /** Determines whether the king of the provided [color] can castle according to [castling] given [set]. */
    private fun canCastle(color: Piece.Color, castling: Castling, set: PieceSet): Boolean {
        val us = set.get(color)

        val validKing = us and set.get(Piece.Kind.king) and castling.kingStart.bb
        val validRook = us and set.get(Piece.Kind.rook) and castling.rookStart.bb

        val pathClear = castling.path.all { set.get(it) == null }

        val notCastlingThroughCheck = castling.squares.all { (attackers(it.bb, set) and us.inv()) == 0uL }

        val notInCheck = !isKingInCheck(color, set)

        return position.legalCastlings.contains(castling) &&
            validKing != 0uL &&
            validRook != 0uL &&
            pathClear &&
            notCastlingThroughCheck &&
            notInCheck
    }

    override fun toString(): String = position.toString()

    /** Represents a state of the board. */
    sealed interface State {
        /**
         * The board's position represents an active position.
         *
         * This default state indicates there is nothing of note about this position.
         */
        data object active : State

        /**
         * The board's position represents an active piece promotion with the given [move].
         *
         * If this state is received, call [completePromotion] with `move` and the
         * desired promotion kind to complete the promotion.
         */
        data class promotion(val move: Move) : State

        /**
         * The board's position represents a check on the given [color].
         *
         * To get the color of the piece that executed the check use [Piece.Color.opposite].
         */
        data class check(val color: Piece.Color) : State

        /**
         * The board's position represents a checkmate on the given [color].
         *
         * To get the color of the piece that executed the checkmate use [Piece.Color.opposite].
         */
        data class checkmate(val color: Piece.Color) : State

        /** The board's position represents a draw with a given [reason]. */
        data class draw(val reason: DrawReason) : State

        /** The type of draw represented on the board. */
        enum class DrawReason {
            agreement, fiftyMoves, insufficientMaterial, repetition, stalemate
        }
    }
}
