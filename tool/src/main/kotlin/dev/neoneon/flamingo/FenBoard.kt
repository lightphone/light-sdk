package dev.neoneon.flamingo

/**
 * Parses the board-placement field of a FEN string into 8 ranks (rank 8 first, rank 1
 * last — matching standard top-to-bottom board display with White at the bottom), each
 * a list of 8 characters: piece letters as-is (`PNBRQK` white, `pnbrqk` black) or `.` for
 * an empty square.
 */
internal fun parseFenBoard(fen: String): List<List<Char>> {
    val placement = fen.substringBefore(' ')
    return placement.split('/').map { rank ->
        buildList {
            rank.forEach { c ->
                if (c.isDigit()) repeat(c.digitToInt()) { add('.') } else add(c)
            }
        }
    }
}
