package com.thelightphone.chess.engine

import kotlin.math.max
import kotlin.random.Random

/**
 * Alpha-beta search with Michniewski's Simplified Evaluation Function
 * (Chess Programming Wiki) and iterative deepening.
 */
object Search {
    private const val MATE = 32_000
    private const val INF = 32_001

    fun pickMove(
        board: Board,
        level: BotLevel,
        remainingMs: Long?,
        rng: Random,
        cancelled: () -> Boolean = { false },
    ): Int {
        val moves = IntArray(256)
        val n = board.generateLegalMoves(moves)
        if (n == 0) return 0
        if (n == 1) return moves[0]

        val limits = limitsFor(level, remainingMs)
        if (level == BotLevel.EASY && rng.nextFloat() < 0.32f) {
            return moves[rng.nextInt(n)]
        }

        val searchBoard = board.copy()
        var best = moves[0]
        val started = System.nanoTime()
        fun timedOut(): Boolean {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            return elapsed >= limits.maxTimeMs || cancelled()
        }

        var nodes = 0
        fun quiesce(ply: Int, alpha0: Int, beta: Int): Int {
            if (timedOut() || nodes > limits.nodeCap) return evaluate(searchBoard)
            nodes++
            var alpha = alpha0
            val stand = evaluate(searchBoard)
            if (stand >= beta) return stand
            if (stand > alpha) alpha = stand
            val legal = IntArray(256)
            val count = searchBoard.generateLegalMoves(legal)
            if (count == 0) {
                return if (searchBoard.inCheck()) -MATE + ply else 0
            }
            orderMoves(searchBoard, legal, count)
            for (i in 0 until count) {
                val move = legal[i]
                if (moveFlags(move) and FLAG_CAPTURE == 0 && moveFlags(move) and FLAG_PROMO == 0) continue
                searchBoard.makeMove(move)
                val score = -quiesce(ply + 1, -beta, -alpha)
                searchBoard.unmakeMove(move)
                if (score >= beta) return score
                if (score > alpha) alpha = score
                if (timedOut()) break
            }
            return alpha
        }

        fun search(depth: Int, ply: Int, alpha0: Int, beta: Int): Int {
            if (timedOut() || nodes > limits.nodeCap) return evaluate(searchBoard)
            nodes++
            if (searchBoard.isThreefold() || searchBoard.halfmove >= 100 || searchBoard.isInsufficientMaterial()) {
                return 0
            }
            if (depth <= 0) return quiesce(ply, alpha0, beta)

            val legal = IntArray(256)
            val count = searchBoard.generateLegalMoves(legal)
            if (count == 0) {
                return if (searchBoard.inCheck()) -MATE + ply else 0
            }
            orderMoves(searchBoard, legal, count)
            var alpha = alpha0
            var bestScore = -INF
            for (i in 0 until count) {
                searchBoard.makeMove(legal[i])
                val score = -search(depth - 1, ply + 1, -beta, -alpha)
                searchBoard.unmakeMove(legal[i])
                if (score > bestScore) bestScore = score
                if (score > alpha) alpha = score
                if (alpha >= beta) break
                if (timedOut()) break
            }
            return bestScore
        }

        for (depth in 1..limits.maxDepth) {
            var alpha = -INF
            var depthBest = moves[0]
            orderMoves(searchBoard, moves, n)
            for (i in 0 until n) {
                searchBoard.makeMove(moves[i])
                val score = -search(depth - 1, 1, -INF, -alpha)
                searchBoard.unmakeMove(moves[i])
                if (timedOut() && depth > 1) break
                if (score > alpha) {
                    alpha = score
                    depthBest = moves[i]
                }
            }
            best = depthBest
            if (timedOut()) break
        }

        val blunder = when (level) {
            BotLevel.EASY -> 0.18f to 120
            BotLevel.MEDIUM -> 0.16f to 95
            else -> 0f to 0
        }
        if (blunder.first > 0f && rng.nextFloat() < blunder.first) {
            val noisy = evaluate(board) + rng.nextInt(-60, 61)
            var alt = best
            var altScore = Int.MIN_VALUE
            for (i in 0 until n) {
                if (moves[i] == best) continue
                board.makeMove(moves[i])
                val s = -evaluate(board) + rng.nextInt(-40, 41)
                board.unmakeMove(moves[i])
                if (s > altScore) {
                    altScore = s
                    alt = moves[i]
                }
            }
            if (altScore > noisy - blunder.second) best = alt
        }
        return best
    }

    private data class Limits(val maxDepth: Int, val maxTimeMs: Long, val nodeCap: Int)

    private fun limitsFor(level: BotLevel, remainingMs: Long?): Limits {
        val clockCap = remainingMs?.let { max(250L, it / 25) }
        return when (level) {
            BotLevel.EASY -> Limits(1, minOf(350L, clockCap ?: 350L), 8_000)
            BotLevel.MEDIUM -> Limits(1, minOf(400L, clockCap ?: 400L), 10_000)
            BotLevel.HARD -> Limits(4, minOf(2_000L, clockCap ?: 2_000L), 120_000)
            BotLevel.GRAND_MASTER -> Limits(6, minOf(4_500L, clockCap ?: 4_500L), 400_000)
        }
    }

    private fun orderMoves(board: Board, moves: IntArray, n: Int) {
        val scores = IntArray(n)
        for (i in 0 until n) {
            val move = moves[i]
            var s = 0
            if (moveFlags(move) and FLAG_PROMO != 0) s += 800 + movePromo(move) * 10
            if (moveFlags(move) and FLAG_CAPTURE != 0) {
                val victim = if (moveFlags(move) and FLAG_EP != 0) {
                    PAWN
                } else {
                    typeOf(board.squares[moveTo(move)])
                }
                s += victim * 16 - typeOf(board.squares[moveFrom(move)])
            }
            scores[i] = s
        }
        for (i in 1 until n) {
            val m = moves[i]
            val sc = scores[i]
            var j = i
            while (j > 0 && scores[j - 1] < sc) {
                moves[j] = moves[j - 1]
                scores[j] = scores[j - 1]
                j--
            }
            moves[j] = m
            scores[j] = sc
        }
    }
}

internal fun evaluate(board: Board): Int {
    var score = 0
    for (sq in 0 until 128) {
        if (sq and 0x88 != 0) continue
        val p = board.squares[sq]
        if (p == EMPTY) continue
        val type = typeOf(p)
        val table = PST[type] ?: continue
        val idx = if (colorOf(p) == WHITE) {
            (7 - rankOf(sq)) * 8 + fileOf(sq)
        } else {
            rankOf(sq) * 8 + fileOf(sq)
        }
        val value = MATERIAL[type] + table[idx]
        score += if (colorOf(p) == WHITE) value else -value
    }
    return if (board.side == WHITE) score else -score
}

private val MATERIAL = intArrayOf(0, 100, 320, 330, 500, 900, 20_000)

private val PST: Array<IntArray?> = arrayOf(
    null,
    intArrayOf( // pawn
        0, 0, 0, 0, 0, 0, 0, 0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5,
        0, 0, 0, 0, 0, 0, 0, 0,
    ),
    intArrayOf( // knight
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -50, -40, -30, -30, -30, -30, -40, -50,
    ),
    intArrayOf( // bishop
        -20, -10, -10, -10, -10, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 10, 10, 5, 0, -10,
        -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 10, 10, 10, 10, 0, -10,
        -10, 10, 10, 10, 10, 10, 10, -10,
        -10, 5, 0, 0, 0, 0, 5, -10,
        -20, -10, -10, -10, -10, -10, -10, -20,
    ),
    intArrayOf( // rook
        0, 0, 0, 0, 0, 0, 0, 0,
        5, 10, 10, 10, 10, 10, 10, 5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        0, 0, 0, 5, 5, 0, 0, 0,
    ),
    intArrayOf( // queen
        -20, -10, -10, -5, -5, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 5, 5, 5, 0, -10,
        -5, 0, 5, 5, 5, 5, 0, -5,
        0, 0, 5, 5, 5, 5, 0, -5,
        -10, 5, 5, 5, 5, 5, 0, -10,
        -10, 0, 5, 0, 0, 0, 0, -10,
        -20, -10, -10, -5, -5, -10, -10, -20,
    ),
    intArrayOf( // king
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -20, -30, -30, -40, -40, -30, -30, -20,
        -10, -20, -20, -20, -20, -20, -20, -10,
        20, 20, 0, 0, 0, 0, 20, 20,
        20, 30, 10, 0, 0, 10, 30, 20,
    ),
)
