package dev.neoneon.flamingo

import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Move
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square

/**
 * The hot seat game's rules: both colors played on this device, one after the other.
 *
 * Plain Kotlin — no Android, no coroutines — so all of it runs in a JVM test. Saving is the
 * caller's job: everything that changes [moves] says so in its return value.
 *
 * Turning the board to face the next player is the screen's job, because it is an animation: the
 * model only says whether the board faces the wrong way ([needsTurn]) and turns it when asked
 * ([turnToSideToMove]).
 *
 * Mirrors iOS's `HotSeatGameModel`.
 */
internal class HotSeatGameModel(storedMoves: List<String> = emptyList()) {

    /** Every move recorded so far, in LAN. A promotion joins only once its piece is picked. */
    var moves: List<String> = playablePrefix(storedMoves)
        private set

    /**
     * The stored list stopped being legal part-way (corrupt, or written by a build that disagreed
     * about the rules) and was cut at the last legal move. The caller saves [moves] back.
     */
    val wasTrimmed: Boolean = moves.size != storedMoves.size

    private var board = Board()
    private var lastMove: Move? = null

    /** A pawn that reached the last rank and is waiting for its piece. Not yet in [moves]. */
    var pendingPromotion: Move? = null
        private set

    /** The color at the bottom of the board. */
    var orientation: Piece.Color = Piece.Color.white
        private set

    init {
        rebuild(facing = null)
    }

    val position: Position get() = board.position

    val sideToMove: Piece.Color get() = board.position.sideToMove

    /**
     * The board faces the player who just moved rather than the one whose turn it is. Never true
     * while a promotion is being picked: chesskit has already handed the turn over, but the move
     * isn't finished.
     */
    val needsTurn: Boolean get() = pendingPromotion == null && orientation != sideToMove

    /** `null` while the game is on, including mid-promotion (chesskit reports `promotion` then). */
    val outcome: GameOutcome? get() = gameOutcome(board.state, resignedColor = null)

    val canTakeBack: Boolean get() = moves.isNotEmpty() || pendingPromotion != null

    /** Only a finished game is worth keeping: one still being played has no result to show. */
    val canSaveToArchive: Boolean get() = outcome != null

    /** The square of the king in check, either color. */
    val kingInCheck: Square? get() = checkedKingSquare(board.state, board.position)

    /** The last recorded move's squares. Hidden mid-promotion, when the pawn is still in the air. */
    val lastMoveSquares: Pair<Square, Square>?
        get() = lastMove?.takeIf { pendingPromotion == null }?.let { it.start to it.end }

    private val isPlayable: Boolean get() = pendingPromotion == null && outcome == null

    /** Where the piece on [square] can go, if it belongs to the side to move and the game is on. */
    fun legalTargets(square: Square): Set<Square> {
        if (!isPlayable || board.position.piece(at = square)?.color != sideToMove) return emptySet()
        return board.legalMoves(forPieceAt = square).toSet()
    }

    /**
     * Plays [from]→[to] for the side to move. Returns `true` when [moves] changed — so `false` for
     * an illegal or out-of-turn move, and for a pawn reaching the last rank, which waits in
     * [pendingPromotion] until [completePromotion].
     */
    fun play(from: Square, to: Square): Boolean {
        if (!isPlayable) return false
        // chesskit's Board.move doesn't check whose turn it is.
        if (board.position.piece(at = from)?.color != sideToMove) return false
        val move = board.move(pieceAt = from, to = to) ?: return false
        if (board.state is Board.State.promotion) {
            pendingPromotion = move
            return false
        }
        record(move)
        return true
    }

    /** Finishes the pending promotion with [kind]. Returns `true` when [moves] changed. */
    fun completePromotion(kind: Piece.Kind): Boolean {
        val pending = pendingPromotion ?: return false
        pendingPromotion = null
        record(board.completePromotion(pending, kind))
        return true
    }

    /**
     * Puts the pawn of a pending promotion back. The move was never recorded, so rebuilding the
     * board without it is the whole undo.
     */
    fun cancelPromotion() {
        if (pendingPromotion == null) return
        rebuild(facing = orientation)
    }

    /**
     * Takes back the last move. Repeatable down to the start position; there is no redo. A
     * promotion still waiting for its piece is the move taken back, as it was never played.
     *
     * Keeps the board facing as it was, so the screen can animate the turn back. Returns `true`
     * when [moves] changed.
     */
    fun takeBack(): Boolean {
        if (!canTakeBack) return false
        val dropsRecordedMove = pendingPromotion == null
        if (dropsRecordedMove) moves = moves.dropLast(1)
        rebuild(facing = orientation)
        return dropsRecordedMove
    }

    /** Back to the start position, still facing as it was so the screen can animate the turn. */
    fun reset() {
        moves = emptyList()
        rebuild(facing = orientation)
    }

    /** Faces the board to the side to move. The screen calls this once its fade-out has played. */
    fun turnToSideToMove() {
        orientation = sideToMove
    }

    private fun record(move: Move) {
        moves = moves + move.lan
        lastMove = move
    }

    /**
     * Rebuilds the board by replaying [moves] on a fresh one, which is what keeps chesskit's
     * repetition counts intact. [facing] keeps the board turned the way it was; `null` faces the
     * side to move straight away (opening the game).
     */
    private fun rebuild(facing: Piece.Color?) {
        val replayed = Board()
        var last: Move? = null
        // Only ever moves playablePrefix or record has vetted, so none of these fails.
        for (lan in moves) last = playLan(replayed, lan)
        board = replayed
        lastMove = last
        pendingPromotion = null
        orientation = facing ?: replayed.position.sideToMove
    }

    companion object {
        private val lanPattern = Regex("^([a-h][1-8]){2}[qrbn]?$")

        /** The longest run of [moves], from the start, that is legal chess in a game still on. */
        fun playablePrefix(moves: List<String>): List<String> {
            val board = Board()
            return moves.takeWhile { lan ->
                gameOutcome(board.state, resignedColor = null) == null && playLan(board, lan) != null
            }
        }

        /**
         * Plays one LAN move on [board], promotion included, and returns it. `null` — possibly
         * leaving [board] half-moved — if it isn't a legal move for the side to move, or carries a
         * promotion piece exactly when it shouldn't.
         */
        fun playLan(board: Board, lan: String): Move? {
            // Square("zz") quietly reads as a1, so the shape is checked up front.
            if (!lanPattern.matches(lan)) return null
            val from = Square(lan.substring(0, 2))
            val to = Square(lan.substring(2, 4))
            if (board.position.piece(at = from)?.color != board.position.sideToMove) return null
            val move = board.move(pieceAt = from, to = to) ?: return null
            val promotion = lan.getOrNull(4)?.let { Piece.Kind.fromRawValue(it.uppercase()) }
            return when {
                board.state is Board.State.promotion -> promotion?.let { board.completePromotion(move, it) }
                promotion != null -> null
                else -> move
            }
        }
    }
}

/**
 * Every position the game passed through, for the archive's replay: the start position, then one
 * after each move, with that move's squares and any check it gave. Stops where [playablePrefix]
 * would — at a move that isn't legal, or one played after the game was already over.
 *
 * Mirrors iOS's `HotSeatGame.positions`.
 */
internal fun HotSeatGame.replayFrames(): List<ReplayFrame> {
    val board = Board()
    val frames = mutableListOf(ReplayFrame(position = board.position))
    for (lan in moves) {
        if (gameOutcome(board.state, resignedColor = null) != null) break
        val move = HotSeatGameModel.playLan(board, lan) ?: break
        frames += ReplayFrame(
            position = board.position,
            lastMove = move.start to move.end,
            checkedKingSquare = checkedKingSquare(board.state, board.position),
        )
    }
    return frames
}
