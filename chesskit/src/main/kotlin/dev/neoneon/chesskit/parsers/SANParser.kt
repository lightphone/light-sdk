package dev.neoneon.chesskit

/** Parses and converts the Standard Algebraic Notation (SAN) of a chess move. */
object SANParser {

    // MARK: Public

    /**
     * Parses a SAN string and returns a move.
     *
     * @param san The SAN string of a move.
     * @param position The current chess position to make the move from.
     * @return A representation of a move, or `null` if the SAN is invalid.
     *
     * Note: Make sure the provided [position] has the correct `sideToMove` set or
     * the parsing may fail due to invalid moves.
     */
    fun parse(san: String, position: Position): Move? {
        if (!isValid(san)) return null

        val color = position.sideToMove
        var checkState = Move.CheckState.none

        if (san.contains("#")) {
            checkState = Move.CheckState.checkmate
        } else if (san.contains("+")) {
            checkState = Move.CheckState.check
        }

        // castling
        var castling: Castling? = null

        if (Pattern.shortCastle.containsMatchIn(san)) {
            castling = Castling(Castling.Side.king, color)
        } else if (Pattern.longCastle.containsMatchIn(san)) {
            castling = Castling(Castling.Side.queen, color)
        }

        if (castling != null) {
            return Move(
                result = Move.Result.castle(castling),
                piece = Piece(Piece.Kind.king, color, castling.kingStart),
                start = castling.kingStart,
                end = castling.kingEnd,
                checkState = checkState,
            )
        }

        // pawns
        val pawnFileMatch = Pattern.pawnFile.find(san)
        val end = targetSquare(san)

        if (pawnFileMatch != null && end != null) {
            val startingFile = pawnFileMatch.value

            val board = Board(position)
            val possiblePawn = position.pieces
                .filter { it.kind == Piece.Kind.pawn && it.color == color && it.square.file == Square.File.fromSymbol(startingFile) }
                .firstOrNull { board.canMove(pieceAt = it.square, to = end) }

            if (possiblePawn == null) return null

            val start = possiblePawn.square
            val pawn = possiblePawn.copy(square = end)

            var move: Move? = null

            if (isCapture(san)) {
                val capturedPiece = position.piece(at = end)
                val ep = position.enPassant

                if (capturedPiece != null) {
                    move = Move(result = Move.Result.capture(capturedPiece), piece = pawn, start = start, end = capturedPiece.square, checkState = checkState)
                } else if (ep != null && ep.captureSquare == end) {
                    move = Move(result = Move.Result.capture(ep.pawn), piece = pawn, start = start, end = end, checkState = checkState)
                }
            } else {
                move = Move(result = Move.Result.move, piece = pawn, start = start, end = end, checkState = checkState)
            }

            val promotionPieceKind = promotionPiece(san)
            if (promotionPieceKind != null) {
                move = move?.copy(promotedPiece = Piece(promotionPieceKind, color, end))
            }

            return move
        }

        // pieces
        val pieceKindMatch = Pattern.pieceKind.find(san)
        val pieceKind = pieceKindMatch?.let { Piece.Kind.fromRawValue(it.value) }
        val pieceEnd = targetSquare(san)

        if (pieceKind == null || pieceEnd == null) return null

        val disambiguation = disambiguation(san)

        val board = Board(position)
        val possiblePiece = position.pieces
            .filter { it.kind == pieceKind && it.color == color }
            .filter { board.canMove(pieceAt = it.square, to = pieceEnd) }
            .filter {
                when (disambiguation) {
                    is Move.Disambiguation.byFile -> it.square.file == disambiguation.file
                    is Move.Disambiguation.byRank -> it.square.rank == disambiguation.rank
                    is Move.Disambiguation.bySquare -> it.square == disambiguation.square
                    null -> true
                }
            }
            .firstOrNull()

        if (possiblePiece == null) return null

        val start = possiblePiece.square
        val piece = possiblePiece.copy(square = pieceEnd)

        val move = if (isCapture(san)) {
            val capturedPiece = position.piece(at = pieceEnd) ?: return null
            Move(result = Move.Result.capture(capturedPiece), piece = piece, start = start, end = pieceEnd, checkState = checkState)
        } else {
            Move(result = Move.Result.move, piece = piece, start = start, end = pieceEnd, checkState = checkState)
        }

        return move.copy(disambiguation = disambiguation)
    }

    /**
     * Converts a [Move] object into a SAN string.
     *
     * @param move The chess move to convert.
     * @return A string containing the SAN of [move].
     */
    fun convert(move: Move): String {
        val result = move.result

        return if (result is Move.Result.castle) {
            "${result.castling.side.notation}${move.checkState.notation}"
        } else {
            var pieceNotation = move.piece.kind.notation

            if (move.piece.kind == Piece.Kind.pawn && result is Move.Result.capture) {
                pieceNotation = move.start.file.symbol.toString()
            }

            var disambiguationNotation = ""

            when (val disambiguation = move.disambiguation) {
                is Move.Disambiguation.byFile -> disambiguationNotation = disambiguation.file.symbol.toString()
                is Move.Disambiguation.byRank -> disambiguationNotation = "${disambiguation.rank.value}"
                is Move.Disambiguation.bySquare -> disambiguationNotation = disambiguation.square.notation
                null -> {}
            }

            val captureNotation = if (result is Move.Result.capture) "x" else ""

            var promotionNotation = ""

            val promotedPiece = move.promotedPiece
            if (promotedPiece != null) {
                promotionNotation = "=${promotedPiece.kind.notation}"
            }

            "$pieceNotation$disambiguationNotation$captureNotation${move.end.notation}$promotionNotation${move.checkState.notation}"
        }
    }

    // MARK: Private

    /** Returns whether the provided SAN is valid. */
    private fun isValid(san: String): Boolean = Pattern.full.matches(san)

    /**
     * Returns the target square for a SAN move.
     *
     * @param san The SAN representation of a move.
     * @return The square the move is targeting, or `null` if the SAN is invalid.
     */
    private fun targetSquare(san: String): Square? {
        val match = Pattern.targetSquare.find(san) ?: return null
        return Square(match.value)
    }

    /**
     * Checks if a SAN string contains a capture.
     *
     * @param san The SAN representation of a move.
     * @return Whether or not the move represents a capture.
     */
    private fun isCapture(san: String): Boolean = san.contains("x")

    /**
     * Checks if a SAN string contains a promotion.
     *
     * @param san The SAN representation of a move.
     * @return The kind of piece that is being promoted to, or `null` if the SAN
     * does not contain a promotion.
     */
    private fun promotionPiece(san: String): Piece.Kind? {
        val match = Pattern.promotion.find(san) ?: return null
        return Piece.Kind.fromRawValue(match.value.replace("=", ""))
    }

    /**
     * Checks if a SAN string contains a disambiguation.
     *
     * @param san The SAN representation of a move.
     * @return The disambiguation contained within the SAN, or `null` if there is none.
     *
     * If multiple pieces of the same type can move to the target square, the SAN
     * contains a disambiguating file, rank, or square so the piece that is moving
     * can be determined.
     */
    private fun disambiguation(san: String): Move.Disambiguation? {
        val match = Pattern.disambiguation.find(san) ?: return null
        val value = match.value

        if (value.isEmpty()) return null

        val rankMatch = Pattern.rank.find(value)
        val fileMatch = Pattern.file.find(value)
        val squareMatch = Pattern.square.find(value)

        return when {
            rankMatch != null -> Move.Disambiguation.byRank(Square.Rank(rankMatch.value.toInt()))
            fileMatch != null -> Move.Disambiguation.byFile(Square.File.fromSymbol(fileMatch.value))
            squareMatch != null -> Move.Disambiguation.bySquare(Square(squareMatch.value))
            else -> null
        }
    }

    /** Contains useful regex patterns for SAN parsing. */
    private object Pattern {
        val full = Regex("^([Oo0]-[Oo0](-[Oo0])?|[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](=[QRBN])?[+#]?)$")

        // piece kinds
        val pawnFile = Regex("^[a-h]")
        val pieceKind = Regex("^[KQRBN]")

        // castling
        val shortCastle = Regex("^[Oo0]-[Oo0]\\+?#?$")
        val longCastle = Regex("^[Oo0]-[Oo0]-[Oo0]\\+?#?$")

        // disambiguation
        val disambiguation = Regex("[a-h]?[1-8]?(?=([a-h][1-8][#+]?)$)")
        val rank = Regex("^[1-8]$")
        val file = Regex("^[a-h]$")
        val square = Regex("^[a-h][1-8]$")

        // other
        val promotion = Regex("=[QRBN]")
        val targetSquare = Regex("([a-h][1-8])(?!([a-h][1-8]))")
    }
}
