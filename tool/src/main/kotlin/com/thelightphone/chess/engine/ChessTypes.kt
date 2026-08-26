package com.thelightphone.chess.engine

const val EMPTY = 0
const val PAWN = 1
const val KNIGHT = 2
const val BISHOP = 3
const val ROOK = 4
const val QUEEN = 5
const val KING = 6

const val WHITE = 8
const val BLACK = 16

const val CASTLE_WK = 1
const val CASTLE_WQ = 2
const val CASTLE_BK = 4
const val CASTLE_BQ = 8

const val FLAG_CAPTURE = 1
const val FLAG_EP = 2
const val FLAG_CASTLE = 4
const val FLAG_PAWN_START = 8
const val FLAG_PROMO = 16

const val A1 = 0
const val B1 = 1
const val C1 = 2
const val D1 = 3
const val E1 = 4
const val F1 = 5
const val G1 = 6
const val H1 = 7
const val A8 = 112
const val B8 = 113
const val C8 = 114
const val D8 = 115
const val E8 = 116
const val F8 = 117
const val G8 = 118
const val H8 = 119

fun typeOf(piece: Int): Int = piece and 7
fun colorOf(piece: Int): Int = piece and (WHITE or BLACK)
fun colorIndex(piece: Int): Int = if (colorOf(piece) == WHITE) 0 else 1
fun opposite(color: Int): Int = if (color == WHITE) BLACK else WHITE
fun fileOf(sq: Int): Int = sq and 7
fun rankOf(sq: Int): Int = sq shr 4
fun sq64(sq: Int): Int = (sq shr 4) * 8 + (sq and 7)
fun isDarkSquare(sq: Int): Boolean = ((sq and 7) + (sq shr 4)) and 1 == 0

fun pieceIndex(piece: Int): Int {
    val type = typeOf(piece) - 1
    return if (colorOf(piece) == WHITE) type else type + 6
}

fun pieceFromFen(ch: Char): Int {
    val type = when (ch.lowercaseChar()) {
        'p' -> PAWN
        'n' -> KNIGHT
        'b' -> BISHOP
        'r' -> ROOK
        'q' -> QUEEN
        'k' -> KING
        else -> error("Bad FEN piece: $ch")
    }
    return type or if (ch.isUpperCase()) WHITE else BLACK
}

fun fenChar(piece: Int): Char {
    val ch = when (typeOf(piece)) {
        PAWN -> 'p'
        KNIGHT -> 'n'
        BISHOP -> 'b'
        ROOK -> 'r'
        QUEEN -> 'q'
        KING -> 'k'
        else -> '?'
    }
    return if (colorOf(piece) == WHITE) ch.uppercaseChar() else ch
}

fun pieceLetter(piece: Int): String {
    val ch = when (typeOf(piece)) {
        PAWN -> "P"
        KNIGHT -> "N"
        BISHOP -> "B"
        ROOK -> "R"
        QUEEN -> "Q"
        KING -> "K"
        else -> ""
    }
    return if (colorOf(piece) == WHITE) ch else ch.lowercase()
}

fun squareName(sq: Int): String = "${'a' + fileOf(sq)}${'1' + rankOf(sq)}"

fun parseSquare(name: String): Int {
    require(name.length == 2) { "Bad square: $name" }
    val file = name[0] - 'a'
    val rank = name[1] - '1'
    require(file in 0..7 && rank in 0..7) { "Bad square: $name" }
    return rank * 16 + file
}

fun typeFromPromoChar(ch: Char): Int = when (ch.lowercaseChar()) {
    'n' -> KNIGHT
    'b' -> BISHOP
    'r' -> ROOK
    'q' -> QUEEN
    else -> QUEEN
}

fun encodeMove(from: Int, to: Int, promo: Int, flags: Int): Int =
    from or (to shl 7) or (promo shl 14) or (flags shl 17)

fun moveFrom(move: Int): Int = move and 127
fun moveTo(move: Int): Int = (move shr 7) and 127
fun movePromo(move: Int): Int = (move shr 14) and 7
fun moveFlags(move: Int): Int = move shr 17

data class ChessMove(
    val encoded: Int,
) {
    val from: Int get() = moveFrom(encoded)
    val to: Int get() = moveTo(encoded)
    val promotion: Int get() = movePromo(encoded)
    val isPromotion: Boolean get() = moveFlags(encoded) and FLAG_PROMO != 0

    fun uci(): String {
        val promo = if (isPromotion) {
            when (promotion) {
                KNIGHT -> "n"
                BISHOP -> "b"
                ROOK -> "r"
                else -> "q"
            }
        } else {
            ""
        }
        return squareName(from) + squareName(to) + promo
    }

    companion object {
        fun decode(encoded: Int) = ChessMove(encoded)
    }
}

enum class GameResult {
    WIN,
    LOSS,
    DRAW,
    FLAG_WIN,
    FLAG_LOSS,
}

enum class BotLevel {
    EASY,
    MEDIUM,
    HARD,
    GRAND_MASTER,
    ;

    val label: String
        get() = when (this) {
            EASY -> "Easy"
            MEDIUM -> "Medium"
            HARD -> "Hard"
            GRAND_MASTER -> "Grand master"
        }
}

enum class GameTimer(val durationMs: Long?) {
    NONE(null),
    FIVE(5 * 60_000L),
    TEN(10 * 60_000L),
    THIRTY(30 * 60_000L),
    ;

    val label: String
        get() = when (this) {
            NONE -> "No timer"
            FIVE -> "5 min"
            TEN -> "10 min"
            THIRTY -> "30 min"
        }
}

enum class StartColor {
    WHITE,
    BLACK,
    RANDOM,
    ;

    val label: String
        get() = when (this) {
            WHITE -> "White"
            BLACK -> "Black"
            RANDOM -> "Random"
        }
}
