package com.thelightphone.chess.engine

/**
 * 0x88 mailbox chess position. Move generation, make/unmake, and attack tests
 * follow the 0x88 method from the Chess Programming Wiki.
 */
class Board {
    val squares = IntArray(128)
    var side: Int = WHITE
    var castle: Int = 0
    var epSquare: Int = -1
    var halfmove: Int = 0
    var fullmove: Int = 1
    val kingSq = intArrayOf(-1, -1)

    private val undos = Array(512) { Undo() }
    private var ply: Int = 0
    private val hashHistory = LongArray(512)

    val whiteToMove: Boolean get() = side == WHITE

    fun copy(): Board {
        val b = Board()
        squares.copyInto(b.squares)
        b.side = side
        b.castle = castle
        b.epSquare = epSquare
        b.halfmove = halfmove
        b.fullmove = fullmove
        kingSq.copyInto(b.kingSq)
        b.ply = ply
        hashHistory.copyInto(b.hashHistory, endIndex = ply + 1)
        return b
    }

    fun clear() {
        squares.fill(EMPTY)
        side = WHITE
        castle = 0
        epSquare = -1
        halfmove = 0
        fullmove = 1
        kingSq[0] = -1
        kingSq[1] = -1
        ply = 0
    }

    fun loadFen(fen: String) {
        clear()
        val parts = fen.trim().split(Regex("\\s+"))
        require(parts.size >= 4) { "Invalid FEN: $fen" }

        var sq = A8
        for (ch in parts[0]) {
            when {
                ch == '/' -> sq -= 24
                ch.isDigit() -> sq += ch - '0'
                else -> {
                    val piece = pieceFromFen(ch)
                    squares[sq] = piece
                    if (typeOf(piece) == KING) {
                        kingSq[colorIndex(piece)] = sq
                    }
                    sq++
                }
            }
        }

        side = if (parts[1] == "w") WHITE else BLACK
        castle = 0
        if (parts[2] != "-") {
            for (ch in parts[2]) {
                castle = castle or when (ch) {
                    'K' -> CASTLE_WK
                    'Q' -> CASTLE_WQ
                    'k' -> CASTLE_BK
                    'q' -> CASTLE_BQ
                    else -> 0
                }
            }
        }
        epSquare = if (parts[3] == "-") -1 else parseSquare(parts[3])
        halfmove = parts.getOrNull(4)?.toInt() ?: 0
        fullmove = parts.getOrNull(5)?.toInt() ?: 1
        ply = 0
        hashHistory[0] = computeHash()
    }

    fun toFen(): String = buildString {
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val p = squares[rank * 16 + file]
                if (p == EMPTY) {
                    empty++
                } else {
                    if (empty > 0) {
                        append(empty)
                        empty = 0
                    }
                    append(fenChar(p))
                }
            }
            if (empty > 0) append(empty)
            if (rank > 0) append('/')
        }
        append(' ')
        append(if (side == WHITE) 'w' else 'b')
        append(' ')
        val rights = buildString {
            if (castle and CASTLE_WK != 0) append('K')
            if (castle and CASTLE_WQ != 0) append('Q')
            if (castle and CASTLE_BK != 0) append('k')
            if (castle and CASTLE_BQ != 0) append('q')
        }
        append(rights.ifEmpty { "-" })
        append(' ')
        append(if (epSquare >= 0) squareName(epSquare) else "-")
        append(' ')
        append(halfmove)
        append(' ')
        append(fullmove)
    }

    fun inCheck(color: Int = side): Boolean {
        val ksq = kingSq[if (color == WHITE) 0 else 1]
        return ksq >= 0 && isSquareAttacked(ksq, opposite(color))
    }

    fun isCheckmate(): Boolean = legalMoveCount() == 0 && inCheck()

    fun isStalemate(): Boolean = legalMoveCount() == 0 && !inCheck()

    fun isDraw(): Boolean =
        isStalemate() || isInsufficientMaterial() || halfmove >= 100 || isThreefold()

    fun isInsufficientMaterial(): Boolean {
        var pawnsOrMajor = 0
        var knights = 0
        var bishops = 0
        var lightBishop = 0
        var darkBishop = 0
        for (sq in 0 until 128) {
            if (sq and 0x88 != 0) continue
            val p = squares[sq]
            if (p == EMPTY) continue
            when (typeOf(p)) {
                PAWN, ROOK, QUEEN -> pawnsOrMajor++
                KNIGHT -> knights++
                BISHOP -> {
                    bishops++
                    if (isDarkSquare(sq)) darkBishop++ else lightBishop++
                }
            }
        }
        if (pawnsOrMajor > 0) return false
        if (knights + bishops <= 1) return true
        if (knights == 0 && bishops > 0 && (lightBishop == 0 || darkBishop == 0)) return true
        return false
    }

    fun isThreefold(): Boolean {
        val current = hashHistory[ply]
        var count = 0
        for (i in 0..ply) {
            if (hashHistory[i] == current) count++
        }
        return count >= 3
    }

    fun gameResult(playerIsWhite: Boolean): GameResult? {
        if (isCheckmate()) {
            val whiteWon = side == BLACK
            return if (whiteWon == playerIsWhite) GameResult.WIN else GameResult.LOSS
        }
        if (isDraw()) return GameResult.DRAW
        return null
    }

    fun legalMoves(): List<ChessMove> {
        val raw = IntArray(256)
        val n = generateLegalMoves(raw)
        return List(n) { ChessMove.decode(raw[it]) }
    }

    fun legalMovesFrom(from: Int): List<ChessMove> = legalMoves().filter { it.from == from }

    fun legalMoveCount(): Int {
        val raw = IntArray(256)
        return generateLegalMoves(raw)
    }

    fun generateLegalMoves(out: IntArray): Int {
        val pseudo = IntArray(256)
        val n = generatePseudoLegal(pseudo)
        var count = 0
        val us = side
        for (i in 0 until n) {
            val move = pseudo[i]
            makeMove(move)
            val legal = !inCheck(us)
            unmakeMove(move)
            if (legal) out[count++] = move
        }
        return count
    }

    fun makeMove(move: Int) {
        val from = moveFrom(move)
        val to = moveTo(move)
        val promo = movePromo(move)
        val flags = moveFlags(move)
        val piece = squares[from]
        val us = side
        val them = opposite(us)

        val undo = undos[ply]
        undo.captured = squares[to]
        undo.ep = epSquare
        undo.castle = castle
        undo.halfmove = halfmove

        halfmove = if (typeOf(piece) == PAWN || squares[to] != EMPTY || flags and FLAG_EP != 0) {
            0
        } else {
            halfmove + 1
        }
        epSquare = -1

        if (flags and FLAG_EP != 0) {
            val capSq = to + if (us == WHITE) -16 else 16
            undo.captured = squares[capSq]
            squares[capSq] = EMPTY
        } else if (squares[to] != EMPTY && typeOf(squares[to]) == KING) {
            kingSq[if (them == WHITE) 0 else 1] = -1
        }

        squares[to] = if (flags and FLAG_PROMO != 0) promo or us else piece
        squares[from] = EMPTY

        if (typeOf(piece) == KING) {
            kingSq[if (us == WHITE) 0 else 1] = to
            if (flags and FLAG_CASTLE != 0) {
                if (to > from) {
                    squares[from + 1] = squares[from + 3]
                    squares[from + 3] = EMPTY
                } else {
                    squares[from - 1] = squares[from - 4]
                    squares[from - 4] = EMPTY
                }
            }
        }

        castle = castle and CASTLE_MASK[from] and CASTLE_MASK[to]
        if (flags and FLAG_PAWN_START != 0) {
            epSquare = (from + to) shr 1
        }
        if (us == BLACK) fullmove++
        side = them
        ply++
        hashHistory[ply] = computeHash()
    }

    fun unmakeMove(move: Int) {
        ply--
        val from = moveFrom(move)
        val to = moveTo(move)
        val flags = moveFlags(move)
        val undo = undos[ply]
        val us = opposite(side)
        val them = side

        side = us
        castle = undo.castle
        epSquare = undo.ep
        halfmove = undo.halfmove
        if (us == BLACK) fullmove--

        var piece = squares[to]
        if (flags and FLAG_PROMO != 0) piece = PAWN or us

        squares[from] = piece
        squares[to] = EMPTY

        if (flags and FLAG_EP != 0) {
            val capSq = to + if (us == WHITE) -16 else 16
            squares[capSq] = undo.captured
        } else {
            squares[to] = undo.captured
            if (undo.captured != EMPTY && typeOf(undo.captured) == KING) {
                kingSq[if (them == WHITE) 0 else 1] = to
            }
        }

        if (typeOf(piece) == KING) {
            kingSq[if (us == WHITE) 0 else 1] = from
            if (flags and FLAG_CASTLE != 0) {
                if (to > from) {
                    squares[from + 3] = squares[from + 1]
                    squares[from + 1] = EMPTY
                } else {
                    squares[from - 4] = squares[from - 1]
                    squares[from - 1] = EMPTY
                }
            }
        }
    }

    fun moveFromUci(uci: String): Int {
        require(uci.length >= 4) { "Invalid UCI: $uci" }
        val from = parseSquare(uci.substring(0, 2))
        val to = parseSquare(uci.substring(2, 4))
        val promo = if (uci.length > 4) typeFromPromoChar(uci[4]) else 0
        val moves = IntArray(256)
        val n = generateLegalMoves(moves)
        for (i in 0 until n) {
            val m = moves[i]
            if (moveFrom(m) == from && moveTo(m) == to) {
                if (promo == 0 || movePromo(m) == promo) return m
            }
        }
        error("Illegal UCI move $uci in ${toFen()}")
    }

    fun applyUci(uci: String) {
        makeMove(moveFromUci(uci))
    }

    fun isSquareAttacked(square: Int, byColor: Int): Boolean {
        val pawnFromDir = if (byColor == WHITE) -16 else 16
        for (fileDelta in intArrayOf(-1, 1)) {
            val from = square + pawnFromDir + fileDelta
            if (from and 0x88 == 0 && squares[from] == (PAWN or byColor)) return true
        }
        for (delta in KNIGHT_DELTAS) {
            val from = square + delta
            if (from and 0x88 == 0 && squares[from] == (KNIGHT or byColor)) return true
        }
        for (delta in KING_DELTAS) {
            val from = square + delta
            if (from and 0x88 == 0 && squares[from] == (KING or byColor)) return true
        }
        if (slideAttacked(square, BISHOP_DELTAS, byColor, BISHOP)) return true
        if (slideAttacked(square, ROOK_DELTAS, byColor, ROOK)) return true
        return false
    }

    private fun slideAttacked(square: Int, deltas: IntArray, byColor: Int, slider: Int): Boolean {
        for (delta in deltas) {
            var to = square + delta
            while (to and 0x88 == 0) {
                val p = squares[to]
                if (p != EMPTY) {
                    if (colorOf(p) == byColor) {
                        val t = typeOf(p)
                        if (t == slider || t == QUEEN) return true
                    }
                    break
                }
                to += delta
            }
        }
        return false
    }

    private fun generatePseudoLegal(out: IntArray): Int {
        var n = 0
        val us = side
        val them = opposite(us)
        val forward = if (us == WHITE) 16 else -16
        val startRank = if (us == WHITE) 1 else 6
        val promoRank = if (us == WHITE) 7 else 0

        for (from in 0 until 128) {
            if (from and 0x88 != 0) continue
            val piece = squares[from]
            if (piece == EMPTY || colorOf(piece) != us) continue
            when (typeOf(piece)) {
                PAWN -> n = genPawn(from, us, them, forward, startRank, promoRank, out, n)
                KNIGHT -> n = addDeltas(from, KNIGHT_DELTAS, them, out, n)
                BISHOP -> n = addSlides(from, BISHOP_DELTAS, them, out, n)
                ROOK -> n = addSlides(from, ROOK_DELTAS, them, out, n)
                QUEEN -> n = addSlides(from, KING_DELTAS, them, out, n)
                KING -> {
                    n = addDeltas(from, KING_DELTAS, them, out, n)
                    n = addCastles(from, us, them, out, n)
                }
            }
        }
        return n
    }

    private fun genPawn(
        from: Int,
        us: Int,
        them: Int,
        forward: Int,
        startRank: Int,
        promoRank: Int,
        out: IntArray,
        start: Int,
    ): Int {
        var n = start
        val one = from + forward
        if (one and 0x88 == 0 && squares[one] == EMPTY) {
            n = addPawnTo(from, one, 0, promoRank, out, n)
            if (rankOf(from) == startRank) {
                val two = from + forward * 2
                if (two and 0x88 == 0 && squares[two] == EMPTY) {
                    out[n++] = encodeMove(from, two, 0, FLAG_PAWN_START)
                }
            }
        }
        for (fileDelta in intArrayOf(-1, 1)) {
            val to = from + forward + fileDelta
            if (to and 0x88 != 0) continue
            val cap = squares[to]
            val isEp = to == epSquare
            if ((cap != EMPTY && colorOf(cap) == them) || isEp) {
                val flags = FLAG_CAPTURE or if (isEp) FLAG_EP else 0
                n = addPawnTo(from, to, flags, promoRank, out, n)
            }
        }
        return n
    }

    private fun addPawnTo(
        from: Int,
        to: Int,
        flags: Int,
        promoRank: Int,
        out: IntArray,
        start: Int,
    ): Int {
        var n = start
        if (rankOf(to) == promoRank) {
            val promoFlags = flags or FLAG_PROMO
            out[n++] = encodeMove(from, to, QUEEN, promoFlags)
            out[n++] = encodeMove(from, to, ROOK, promoFlags)
            out[n++] = encodeMove(from, to, BISHOP, promoFlags)
            out[n++] = encodeMove(from, to, KNIGHT, promoFlags)
        } else {
            out[n++] = encodeMove(from, to, 0, flags)
        }
        return n
    }

    private fun addDeltas(from: Int, deltas: IntArray, them: Int, out: IntArray, start: Int): Int {
        var n = start
        for (delta in deltas) {
            val to = from + delta
            if (to and 0x88 != 0) continue
            val cap = squares[to]
            if (cap == EMPTY) {
                out[n++] = encodeMove(from, to, 0, 0)
            } else if (colorOf(cap) == them) {
                out[n++] = encodeMove(from, to, 0, FLAG_CAPTURE)
            }
        }
        return n
    }

    private fun addSlides(from: Int, deltas: IntArray, them: Int, out: IntArray, start: Int): Int {
        var n = start
        for (delta in deltas) {
            var to = from + delta
            while (to and 0x88 == 0) {
                val cap = squares[to]
                if (cap == EMPTY) {
                    out[n++] = encodeMove(from, to, 0, 0)
                } else {
                    if (colorOf(cap) == them) {
                        out[n++] = encodeMove(from, to, 0, FLAG_CAPTURE)
                    }
                    break
                }
                to += delta
            }
        }
        return n
    }

    private fun addCastles(from: Int, us: Int, them: Int, out: IntArray, start: Int): Int {
        var n = start
        if (inCheck(us)) return n
        if (us == WHITE && from == E1) {
            if (castle and CASTLE_WK != 0 &&
                squares[F1] == EMPTY && squares[G1] == EMPTY &&
                !isSquareAttacked(F1, them) && !isSquareAttacked(G1, them)
            ) {
                out[n++] = encodeMove(E1, G1, 0, FLAG_CASTLE)
            }
            if (castle and CASTLE_WQ != 0 &&
                squares[B1] == EMPTY && squares[C1] == EMPTY && squares[D1] == EMPTY &&
                !isSquareAttacked(D1, them) && !isSquareAttacked(C1, them)
            ) {
                out[n++] = encodeMove(E1, C1, 0, FLAG_CASTLE)
            }
        }
        if (us == BLACK && from == E8) {
            if (castle and CASTLE_BK != 0 &&
                squares[F8] == EMPTY && squares[G8] == EMPTY &&
                !isSquareAttacked(F8, them) && !isSquareAttacked(G8, them)
            ) {
                out[n++] = encodeMove(E8, G8, 0, FLAG_CASTLE)
            }
            if (castle and CASTLE_BQ != 0 &&
                squares[B8] == EMPTY && squares[C8] == EMPTY && squares[D8] == EMPTY &&
                !isSquareAttacked(D8, them) && !isSquareAttacked(C8, them)
            ) {
                out[n++] = encodeMove(E8, C8, 0, FLAG_CASTLE)
            }
        }
        return n
    }

    private fun computeHash(): Long {
        var h = 0L
        for (sq in 0 until 128) {
            if (sq and 0x88 != 0) continue
            val p = squares[sq]
            if (p != EMPTY) h = h xor Zobrist.piece[pieceIndex(p)][sq64(sq)]
        }
        if (side == BLACK) h = h xor Zobrist.side
        h = h xor Zobrist.castle[castle]
        if (epSquare >= 0) h = h xor Zobrist.epFile[fileOf(epSquare)]
        return h
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun start(): Board = Board().also { it.loadFen(START_FEN) }

        fun perft(board: Board, depth: Int): Long {
            if (depth == 0) return 1
            val moves = IntArray(256)
            val n = board.generateLegalMoves(moves)
            if (depth == 1) return n.toLong()
            var nodes = 0L
            for (i in 0 until n) {
                board.makeMove(moves[i])
                nodes += perft(board, depth - 1)
                board.unmakeMove(moves[i])
            }
            return nodes
        }
    }
}

private class Undo {
    var captured: Int = EMPTY
    var ep: Int = -1
    var castle: Int = 0
    var halfmove: Int = 0
}

private object Zobrist {
    val piece = Array(12) { LongArray(64) }
    val castle = LongArray(16)
    val epFile = LongArray(8)
    val side: Long

    init {
        var seed = 0x9E3779B97F4A7C15UL
        fun next(): Long {
            seed = seed xor (seed shl 7)
            seed = seed xor (seed shr 9)
            seed = seed xor (seed shl 8)
            return seed.toLong()
        }
        for (p in 0 until 12) {
            for (s in 0 until 64) piece[p][s] = next()
        }
        for (i in castle.indices) castle[i] = next()
        for (i in epFile.indices) epFile[i] = next()
        side = next()
    }
}

private val CASTLE_MASK: IntArray = IntArray(128) { 0xF }.also { mask ->
    mask[A1] = 0xF xor CASTLE_WQ
    mask[E1] = 0xF xor (CASTLE_WK or CASTLE_WQ)
    mask[H1] = 0xF xor CASTLE_WK
    mask[A8] = 0xF xor CASTLE_BQ
    mask[E8] = 0xF xor (CASTLE_BK or CASTLE_BQ)
    mask[H8] = 0xF xor CASTLE_BK
}

private val KNIGHT_DELTAS = intArrayOf(-33, -31, -18, -14, 14, 18, 31, 33)
private val KING_DELTAS = intArrayOf(-17, -16, -15, -1, 1, 15, 16, 17)
private val BISHOP_DELTAS = intArrayOf(-17, -15, 15, 17)
private val ROOK_DELTAS = intArrayOf(-16, -1, 1, 16)
