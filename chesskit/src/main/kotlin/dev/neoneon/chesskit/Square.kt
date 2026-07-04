package dev.neoneon.chesskit

/** Represents a square on the chess board. */
enum class Square {
    a1, b1, c1, d1, e1, f1, g1, h1,
    a2, b2, c2, d2, e2, f2, g2, h2,
    a3, b3, c3, d3, e3, f3, g3, h3,
    a4, b4, c4, d4, e4, f4, g4, h4,
    a5, b5, c5, d5, e5, f5, g5, h5,
    a6, b6, c6, d6, e6, f6, g6, h6,
    a7, b7, c7, d7, e7, f7, g7, h7,
    a8, b8, c8, d8, e8, f8, g8, h8;

    /** The file on the chess board, from a to h. */
    enum class File(val symbol: Char) {
        a('a'), b('b'), c('c'), d('d'), e('e'), f('f'), g('g'), h('h');

        /**
         * The number corresponding to the file.
         *
         * For example:
         * ```
         * Square.File.a.number // 1
         * Square.File.h.number // 8
         * ```
         */
        val number: Int get() = ordinal + 1

        companion object {
            /**
             * Initialize a file from a number from 1 through 8.
             *
             * If an invalid number is passed, i.e. less than 1 or
             * greater than 8, the file is set to [a].
             *
             * See also [Square.File.number].
             */
            operator fun invoke(number: Int): File = when {
                number < 1 -> a
                number > 8 -> h
                else -> entries[number - 1]
            }

            /** Returns the [File] whose [symbol] matches [symbol], or [a] if not found. */
            fun fromSymbol(symbol: String): File = entries.firstOrNull { it.symbol.toString() == symbol } ?: a
        }
    }

    /** The rank on the chess board, from 1 to 8. */
    data class Rank private constructor(val value: Int) {
        override fun toString(): String = "$value"

        companion object {
            /** The possible range of Rank numbers. */
            val range = 1..8

            /** Initialize a Rank with a provided integer value, bounded to [range]. */
            operator fun invoke(value: Int): Rank = Rank(value.bounded(range))
        }
    }

    companion object {
        /** Initializes a board square from the given notation string, e.g. `"a1"`. */
        operator fun invoke(notation: String): Square {
            val file = File.entries.firstOrNull { it.symbol.toString() == notation.take(1) } ?: File.a
            val rank = Rank(notation.takeLast(1).toIntOrNull() ?: 1)
            return invoke(file, rank)
        }

        /** Initializes a board square from the provided file and rank. */
        operator fun invoke(file: File, rank: Rank): Square {
            val boundedRank = rank.value.bounded(Rank.range)
            return entries[(boundedRank - 1) * 8 + file.ordinal]
        }
    }

    /** The file (column) of the given square, from `a` through `h`. */
    val file: File get() = File.entries[ordinal % 8]

    /** The rank (row) of the given square, from `1` to `8`. */
    val rank: Rank get() = Rank(ordinal / 8 + 1)

    /** The notation for the given square. */
    val notation: String get() = "${file.symbol}${rank.value}"

    /** Represents the possible colors of each board square. */
    enum class Color {
        light, dark
    }

    /** The color of the square on the board, either light or dark. */
    val color: Color
        get() = if ((file.number % 2 == 0 && rank.value % 2 == 0) || (file.number % 2 != 0 && rank.value % 2 != 0)) {
            Color.dark
        } else {
            Color.light
        }

    /**
     * The [Square] to the left of the current one.
     *
     * Returns the same square if called from a square on the A file.
     */
    val left: Square get() = Square(File(file.number - 1), rank)

    /**
     * The [Square] to the right of the current one.
     *
     * Returns the same square if called from a square on the H file.
     */
    val right: Square get() = Square(File(file.number + 1), rank)

    /**
     * The [Square] above the current one.
     *
     * Returns the same square if called from a square on the 8th rank.
     */
    val up: Square get() = Square(file, Rank(rank.value + 1))

    /**
     * The [Square] below the current one.
     *
     * Returns the same square if called from a square on the 1st rank.
     */
    val down: Square get() = Square(file, Rank(rank.value - 1))
}

/** Bounds a value to the given [range]. */
internal fun Int.bounded(range: IntRange): Int = coerceIn(range.first, range.last)

// MARK: - Bitboard

val Square.bb: Bitboard get() = 1uL shl ordinal

fun Square(bb: Bitboard): Square? = Square.entries.getOrNull(bb.toLong().countTrailingZeroBits())

val List<Square>.bb: Bitboard get() = fold(0uL) { acc, square -> acc or square.bb }

val Square.File.bb: Bitboard get() = aFile.east(number - 1)

val Square.Rank.bb: Bitboard get() = rank1Bb.north(value - 1)
