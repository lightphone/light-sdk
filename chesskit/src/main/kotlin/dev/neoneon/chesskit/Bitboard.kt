package dev.neoneon.chesskit

/** Contains [ULong]-based utilities for manipulating chess bitboards. */
typealias Bitboard = ULong

/** Bitboard representing all the squares on the A file. */
val aFile: Bitboard = 0x0101010101010101uL

/** Bitboard representing all the squares on the H file. */
val hFile: Bitboard = aFile shl 7

/** Bitboard representing all the squares on the 1st rank. */
val rank1Bb: Bitboard = 0xFFuL

/** Bitboard representing all the squares on the 8th rank. */
val rank8Bb: Bitboard = rank1Bb shl (8 * 7)

/** Bitboard representing all the dark squares on the board. */
val darkBb: Bitboard = 0xAA55AA55AA55AA55uL

/** Bitboard representing all the light squares on the board. */
val lightBb: Bitboard = darkBb.inv()

/**
 * Translates the receiver [n] columns "east" on an 8x8 grid.
 *
 * [n] should be in the range `[1, 7]`.
 */
fun Bitboard.east(n: Int = 1): Bitboard = (this and hFile.inv()) shl n

/**
 * Translates the receiver [n] columns "west" on an 8x8 grid.
 *
 * [n] should be in the range `[1, 7]`.
 */
fun Bitboard.west(n: Int = 1): Bitboard = (this and aFile.inv()) shr n

/**
 * Translates the receiver [n] rows "north" on an 8x8 grid.
 *
 * [n] should be in the range `[1, 7]`.
 */
fun Bitboard.north(n: Int = 1): Bitboard = this shl (8 * n)

/**
 * Translates the receiver [n] rows "south" on an 8x8 grid.
 *
 * [n] should be in the range `[1, 7]`.
 */
fun Bitboard.south(n: Int = 1): Bitboard = this shr (8 * n)

/**
 * Translates the receiver [n] rows "north" and [n] columns "east" on an 8x8 grid.
 *
 * [n] should be in the range `[1, 7]`.
 */
fun Bitboard.northEast(n: Int = 1): Bitboard = (this and hFile.inv()) shl (9 * n)

/**
 * Translates the receiver [n] rows "north" and [n] columns "west" on an 8x8 grid.
 *
 * [n] should be in the range `[1, 7]`.
 */
fun Bitboard.northWest(n: Int = 1): Bitboard = (this and aFile.inv()) shl (7 * n)

/**
 * Translates the receiver [n] rows "south" and [n] columns "east" on an 8x8 grid.
 *
 * [n] should be in the range `[1, 7]`.
 */
fun Bitboard.southEast(n: Int = 1): Bitboard = (this and hFile.inv()) shr (7 * n)

/**
 * Translates the receiver [n] rows "south" and [n] columns "west" on an 8x8 grid.
 *
 * [n] should be in the range `[1, 7]`.
 */
fun Bitboard.southWest(n: Int = 1): Bitboard = (this and aFile.inv()) shr (9 * n)

/**
 * Converts the [Bitboard] to an 8x8 board representation string.
 *
 * @param occupied The character with which to represent occupied squares.
 * @param empty The character with which to represent unoccupied squares.
 * @param labelRanks Whether or not to label ranks (i.e. 1, 2, 3, ...).
 * @param labelFiles Whether or not to label ranks (i.e. a, b, c, ...).
 * @return A string representing an 8x8 chess board.
 */
fun Bitboard.chessString(
    occupied: Char = '⨯',
    empty: Char = '·',
    labelRanks: Boolean = true,
    labelFiles: Boolean = true,
): String {
    val s = StringBuilder()

    for (rank in Square.Rank.range.reversed()) {
        if (labelRanks) s.append("$rank ")
        val newLine = StringBuilder()

        for (file in Square.File.entries) {
            val sq = Square(file, Square.Rank(rank)).bb
            newLine.append(if (this and sq != 0uL) "$occupied " else "$empty ")
        }

        s.append(newLine.toString().trim())
        s.append("\n")
    }

    val fileLabels = if (labelFiles) "\n  a b c d e f g h" else ""
    return s.toString().trim() + fileLabels
}

/** The list of [Square]s set in this bitboard, in ascending order. */
val Bitboard.squares: List<Square>
    get() {
        val indices = mutableListOf<Int>()
        var bb = this

        while (bb != 0uL) {
            val index = bb.toLong().countTrailingZeroBits()
            indices.add(index)
            bb = bb and (bb - 1uL)
        }

        return indices.mapNotNull { Square.entries.getOrNull(it) }
    }