package dev.neoneon.chesskit

/** Stores pre-generated pseudo-legal attack bitboards for non-pawn piece types. */
internal object Attacks {

    /** Cached king attacks, the map key corresponds to [Square.bb]. */
    val kings: Map<Bitboard, Bitboard> = buildMap {
        Square.entries.forEach { square ->
            val sq = square.bb

            var attacks = sq.east() or sq.west()
            val horizontal = sq or attacks
            attacks = attacks or horizontal.north() or horizontal.south()

            put(sq, attacks)
        }
    }

    /** Cached knight attacks, the map key corresponds to [Square.bb]. */
    val knights: Map<Bitboard, Bitboard> = buildMap {
        Square.entries.forEach { square ->
            val sq = square.bb

            var result = 0uL
            for (shift in listOf(17, 15, 10, 6)) {
                val up = sq shl shift
                if (distance(sq, up) <= 2) {
                    result = result or up
                }

                val down = sq shr shift
                if (distance(sq, down) <= 2) {
                    result = result or down
                }
            }

            put(sq, result)
        }
    }

    /** Cached rook attacks, the list index corresponds to [Square.ordinal]. */
    val rooks: List<Magic> = createMagics(SlidingPieceKind.ROOK)

    /** Cached bishop attacks, the list index corresponds to [Square.ordinal]. */
    val bishops: List<Magic> = createMagics(SlidingPieceKind.BISHOP)

    /** Computes the Chebyshev Distance between two bitboard squares. */
    private fun distance(sq1: Bitboard, sq2: Bitboard): Int {
        val s1 = Square(sq1) ?: return Int.MAX_VALUE
        val s2 = Square(sq2) ?: return Int.MAX_VALUE

        return maxOf(
            kotlin.math.abs(s1.file.number - s2.file.number),
            kotlin.math.abs(s1.rank.value - s2.rank.value),
        )
    }

    // MARK: Sliding Attacks

    /** Piece kinds for which sliding attack magic bitboards can be generated. */
    private enum class SlidingPieceKind { BISHOP, ROOK }

    /**
     * Generates a list containing a [Magic] object for each square on the chess board.
     *
     * Uses a similar technique as Stockfish (see `Stockfish/init_magics`) except with
     * hardcoded magics rather than seeded random generation.
     */
    private fun createMagics(kind: SlidingPieceKind): List<Magic> {
        val magicNumbers = magicNumbers()[kind] ?: return emptyList()

        return Square.entries.map { sq ->
            // determine board edges not including current square
            val edges: Bitboard = ((rank1Bb or rank8Bb) and sq.rank.bb.inv()) or ((aFile or hFile) and sq.file.bb.inv())

            // calculate magic bitboard factors
            val mask = slidingAttacks(kind, sq, 0uL) and edges.inv()
            val magic = Magic(
                magic = magicNumbers[sq.ordinal],
                mask = mask,
            )

            // use Carry-Rippler technique to generate all possible subsets of current "mask"
            //
            // "mask" contains the possible moves on an empty board,
            // "subset" is a subset of those moves that accounts for
            // any possible blocking piece
            var subset = 0uL

            do {
                // calculate magic index
                val key = magic.key(subset)
                // store subset in attacks map
                magic.attacks[key] = slidingAttacks(kind, sq, subset)
                // generate new subset
                subset = (subset - magic.mask) and magic.mask
            } while (subset != 0uL)

            magic
        }
    }

    /**
     * Returns the possible moves for a sliding piece (bishop or rook) accounting for blocking pieces.
     *
     * Note: the first blocking piece encountered in each direction is included in the returned
     * bitboard. It is up to the caller to handle captures or non-capturable pieces (i.e. same color pieces).
     */
    private fun slidingAttacks(kind: SlidingPieceKind, square: Square, occupancy: Bitboard): Bitboard {
        var attacks = 0uL

        // Single square directional moves for given piece.
        val directions: List<(Bitboard) -> Bitboard> = when (kind) {
            SlidingPieceKind.ROOK -> listOf(
                { bb: Bitboard -> bb.north() },
                { bb: Bitboard -> bb.south() },
                { bb: Bitboard -> bb.east() },
                { bb: Bitboard -> bb.west() },
            )

            SlidingPieceKind.BISHOP -> listOf(
                { bb: Bitboard -> bb.northEast() },
                { bb: Bitboard -> bb.northWest() },
                { bb: Bitboard -> bb.southEast() },
                { bb: Bitboard -> bb.southWest() },
            )
        }

        directions.forEach { d ->
            var nextSquare = square.bb

            do {
                nextSquare = d(nextSquare)
                attacks = attacks or nextSquare
            } while (nextSquare != 0uL && (occupancy and nextSquare) == 0uL)
        }

        return attacks
    }

    /**
     * Magic numbers for calculating bishop and rook magic bitboards.
     *
     * Derived by Pradyumna Kannan.
     */
    private fun magicNumbers(): Map<SlidingPieceKind, List<Bitboard>> = mapOf(
        SlidingPieceKind.BISHOP to listOf(
            0x0002020202020200uL, 0x0002020202020000uL, 0x0004010202000000uL, 0x0004040080000000uL,
            0x0001104000000000uL, 0x0000821040000000uL, 0x0000410410400000uL, 0x0000104104104000uL,
            0x0000040404040400uL, 0x0000020202020200uL, 0x0000040102020000uL, 0x0000040400800000uL,
            0x0000011040000000uL, 0x0000008210400000uL, 0x0000004104104000uL, 0x0000002082082000uL,
            0x0004000808080800uL, 0x0002000404040400uL, 0x0001000202020200uL, 0x0000800802004000uL,
            0x0000800400A00000uL, 0x0000200100884000uL, 0x0000400082082000uL, 0x0000200041041000uL,
            0x0002080010101000uL, 0x0001040008080800uL, 0x0000208004010400uL, 0x0000404004010200uL,
            0x0000840000802000uL, 0x0000404002011000uL, 0x0000808001041000uL, 0x0000404000820800uL,
            0x0001041000202000uL, 0x0000820800101000uL, 0x0000104400080800uL, 0x0000020080080080uL,
            0x0000404040040100uL, 0x0000808100020100uL, 0x0001010100020800uL, 0x0000808080010400uL,
            0x0000820820004000uL, 0x0000410410002000uL, 0x0000082088001000uL, 0x0000002011000800uL,
            0x0000080100400400uL, 0x0001010101000200uL, 0x0002020202000400uL, 0x0001010101000200uL,
            0x0000410410400000uL, 0x0000208208200000uL, 0x0000002084100000uL, 0x0000000020880000uL,
            0x0000001002020000uL, 0x0000040408020000uL, 0x0004040404040000uL, 0x0002020202020000uL,
            0x0000104104104000uL, 0x0000002082082000uL, 0x0000000020841000uL, 0x0000000000208800uL,
            0x0000000010020200uL, 0x0000000404080200uL, 0x0000040404040400uL, 0x0002020202020200uL,
        ),
        SlidingPieceKind.ROOK to listOf(
            0x0080001020400080uL, 0x0040001000200040uL, 0x0080081000200080uL, 0x0080040800100080uL,
            0x0080020400080080uL, 0x0080010200040080uL, 0x0080008001000200uL, 0x0080002040800100uL,
            0x0000800020400080uL, 0x0000400020005000uL, 0x0000801000200080uL, 0x0000800800100080uL,
            0x0000800400080080uL, 0x0000800200040080uL, 0x0000800100020080uL, 0x0000800040800100uL,
            0x0000208000400080uL, 0x0000404000201000uL, 0x0000808010002000uL, 0x0000808008001000uL,
            0x0000808004000800uL, 0x0000808002000400uL, 0x0000010100020004uL, 0x0000020000408104uL,
            0x0000208080004000uL, 0x0000200040005000uL, 0x0000100080200080uL, 0x0000080080100080uL,
            0x0000040080080080uL, 0x0000020080040080uL, 0x0000010080800200uL, 0x0000800080004100uL,
            0x0000204000800080uL, 0x0000200040401000uL, 0x0000100080802000uL, 0x0000080080801000uL,
            0x0000040080800800uL, 0x0000020080800400uL, 0x0000020001010004uL, 0x0000800040800100uL,
            0x0000204000808000uL, 0x0000200040008080uL, 0x0000100020008080uL, 0x0000080010008080uL,
            0x0000040008008080uL, 0x0000020004008080uL, 0x0000010002008080uL, 0x0000004081020004uL,
            0x0000204000800080uL, 0x0000200040008080uL, 0x0000100020008080uL, 0x0000080010008080uL,
            0x0000040008008080uL, 0x0000020004008080uL, 0x0000800100020080uL, 0x0000800041000080uL,
            0x00FFFCDDFCED714AuL, 0x007FFCDDFCED714AuL, 0x003FFFCDFFD88096uL, 0x0000040810002101uL,
            0x0001000204080011uL, 0x0001000204000801uL, 0x0001000082000401uL, 0x0001FFFAABFAD1A2uL,
        ),
    )
}

/**
 * Stores the magic factors and attacks for a given piece type (bishop or rook) and square (a1-h8).
 */
internal class Magic(
    /** The magic number used to compute the hash key. */
    private val magic: Bitboard,
    /** The bitmask representing the possible moves on an empty board excluding edges. */
    val mask: Bitboard,
) {
    /** The number of zero bits in the mask, used to calculate the hash key. */
    private val shift: Int = 64 - mask.toLong().countOneBits()

    /** The map of attack bitboards, keyed by the hash key. */
    val attacks: MutableMap<Bitboard, Bitboard> = mutableMapOf()

    /** Returns the hash key for a given [subset] of possible moves. */
    fun key(subset: Bitboard): Bitboard = (subset * magic) shr shift

    /** Returns the attack bitboard for the piece represented by the receiver for the given [occupancy]. */
    fun attacksFor(occupancy: Bitboard): Bitboard = attacks[key(occupancy and mask)] ?: 0uL
}

internal fun List<Magic>.attacks(square: Square, occupancy: Bitboard): Bitboard {
    if (square.ordinal >= size) return 0uL
    return this[square.ordinal].attacksFor(occupancy)
}
