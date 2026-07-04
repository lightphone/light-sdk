package dev.neoneon.chesskit

/** Parses and converts the Portable Game Notation (PGN) of a chess game. */
object PGNParser {

    // MARK: Public

    /**
     * Parses a PGN string and returns a game.
     *
     * @param pgn The PGN string of a chess game.
     * @return A representation of the chess game.
     * @throws Error indicating the first error encountered while parsing [pgn].
     *
     * The parsing implementation is based on the PGN Standard's import format.
     *
     * The starting position is read from the `FEN` tag if the `SetUp` tag is set to
     * `1`. Otherwise the standard starting position is assumed.
     */
    fun parse(pgn: String): Game {
        // initial processing

        val lines = pgn.lines()
            .map { it.trim() }
            // lines beginning with % are ignored
            .filter { it.take(1) != "%" }

        val sections = splitByBlankLines(lines)

        if (sections.size > 2) throw Error.TooManyLineBreaks
        if (sections.isEmpty()) return Game()

        val firstSection = sections[0]

        val tagPairLines = if (sections.size == 2) firstSection else emptyList()
        val moveTextLines = if (sections.size == 2) sections[1] else firstSection

        // parse tags

        val tags = PGNTagParser.gameTags(tagPairLines.joinToString(""))

        // parse movetext

        val game = MoveTextParser.game(moveTextLines.joinToString(" "), startingPosition(tags))

        // return game with tags + movetext

        game.tags = tags
        return game
    }

    /**
     * Converts a [Game] object into a PGN string.
     *
     * @param game The chess game to convert.
     * @return A string containing the PGN of [game].
     *
     * The conversion implementation is based on the PGN Standard's export format.
     */
    fun convert(game: Game): String {
        val pgn = StringBuilder()

        // tags

        game.tags.all.map { it.pgn }.filter { it.isNotEmpty() }.forEach { pgn.append(it).append("\n") }
        game.tags.other.toSortedMap().forEach { (key, value) -> pgn.append("[$key \"$value\"]\n") }

        if (pgn.isNotEmpty()) {
            pgn.append("\n") // extra line between tags and movetext
        }

        // movetext

        for (element in game.moves.pgnRepresentation) {
            when (element) {
                is MoveTree.PGNElement.whiteNumber -> pgn.append("${element.number}. ")
                is MoveTree.PGNElement.blackNumber -> pgn.append("${element.number}... ")
                is MoveTree.PGNElement.move -> pgn.append(movePGN(element.move))
                is MoveTree.PGNElement.positionAssessment -> pgn.append("${element.assessment.rawValue} ")
                is MoveTree.PGNElement.variationStart -> pgn.append("(")
                is MoveTree.PGNElement.variationEnd -> {
                    val trimmed = pgn.toString().trim()
                    pgn.setLength(0)
                    pgn.append(trimmed).append(") ")
                }
            }
        }

        pgn.append(game.tags.result)

        return pgn.toString().trim()
    }

    // MARK: Private

    /** Generates starting position from `"SetUp"` and `"FEN"` tags. */
    private fun startingPosition(tags: Game.Tags): Position {
        val parsedFen = if (tags.setUp == "1") FENParser.parse(tags.fen) else null

        return when {
            tags.setUp == "1" && parsedFen != null -> parsedFen
            tags.setUp == "0" || (tags.setUp.isEmpty() && tags.fen.isEmpty()) -> Position.standard
            else -> throw Error.InvalidSetUpOrFEN
        }
    }

    /** Generates PGN string for the given [move] including assessments and comments. */
    private fun movePGN(move: Move): String {
        val result = StringBuilder()

        result.append(move.san).append(" ")

        if (move.assessment != Move.Assessment.`null`) {
            result.append(move.assessment.rawValue).append(" ")
        }

        if (move.comment.isNotEmpty()) {
            result.append("{${move.comment}} ")
        }

        return result.toString()
    }

    /** Splits [lines] into sections separated by blank lines, omitting empty sections. */
    private fun splitByBlankLines(lines: List<String>): List<List<String>> {
        val sections = mutableListOf<List<String>>()
        var current = mutableListOf<String>()

        for (line in lines) {
            if (line.isEmpty()) {
                if (current.isNotEmpty()) {
                    sections.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(line)
            }
        }

        if (current.isNotEmpty()) sections.add(current)

        return sections
    }

    // MARK: - Error

    /**
     * Possible errors thrown by [PGNParser].
     *
     * These errors are thrown when issues are encountered while scanning and parsing
     * the provided PGN text.
     */
    sealed class Error(message: String) : Exception(message) {
        /**
         * There are too many line breaks in the provided PGN. PGN should contain a
         * single blank line between the tags and move text.
         */
        object TooManyLineBreaks : Error("Too many line breaks in PGN")

        /**
         * If included in the PGN's tag pairs, the `SetUp` tag must be set to either
         * `"0"` or `"1"`.
         *
         * If `"0"`, the `FEN` tag must be blank. If `1`, the `FEN` tag must contain a
         * valid FEN string representing the starting position of the game.
         */
        object InvalidSetUpOrFEN : Error("Invalid SetUp or FEN tag")

        // MARK: Tags

        /**
         * Tags must be surrounded by brackets with an unquoted string (key) followed
         * by a quoted string (value) inside. For example: `[Round "29"]`
         */
        object InvalidTagFormat : Error("Invalid tag format")

        /**
         * Tags must have an open bracket (`[`) and a close bracket (`]`). If there is
         * a close bracket without an open, this error will be thrown.
         */
        object MismatchedTagBrackets : Error("Mismatched tag brackets")

        /** Tag string (value) could not be parsed. */
        object TagStringNotFound : Error("Tag string not found")

        /** Tag symbol (key) could not be parsed. */
        object TagSymbolNotFound : Error("Tag symbol not found")

        /** Tag symbols must be either letters, numbers, or underscores (`_`). */
        data class UnexpectedTagCharacter(val character: String) : Error("Unexpected tag character: $character")

        // MARK: Move Text

        /** The move or position assessment annotation is invalid. */
        data class InvalidAnnotation(val annotation: String) : Error("Invalid annotation: $annotation")

        /** The move SAN is invalid for the implied position given by its location within the PGN string. */
        data class InvalidMove(val san: String) : Error("Invalid move: $san")

        /** The first item in a move text string must be either a number (e.g. `1.`) or a move SAN (e.g. `e4`). */
        object UnexpectedMoveTextToken : Error("Unexpected move text token")

        /** Comments must be enclosed on both sides by braces (`{`, `}`). */
        object UnpairedCommentDelimiter : Error("Unpaired comment delimiter")

        /** Variations must be enclosed on both sides by parentheses (`(`, `)`). */
        object UnpairedVariationDelimiter : Error("Unpaired variation delimiter")
    }

    // MARK: - Tags

    /** Parses PGN tag pairs. */
    private object PGNTagParser {

        fun gameTags(tagString: String): Game.Tags {
            val gameTags = Game.Tags()
            val other = mutableMapOf<String, String>()

            parse(tagString).forEach { (key, value) ->
                when (key.lowercase()) {
                    "event" -> gameTags.event = value
                    "site" -> gameTags.site = value
                    "date" -> gameTags.date = value
                    "round" -> gameTags.round = value
                    "white" -> gameTags.white = value
                    "black" -> gameTags.black = value
                    "result" -> gameTags.result = value
                    "annotator" -> gameTags.annotator = value
                    "plycount" -> gameTags.plyCount = value
                    "timecontrol" -> gameTags.timeControl = value
                    "time" -> gameTags.time = value
                    "termination" -> gameTags.termination = value
                    "mode" -> gameTags.mode = value
                    "fen" -> gameTags.fen = value
                    "setup" -> gameTags.setUp = value
                    else -> other[key] = value
                }
            }

            gameTags.other = other
            return gameTags
        }

        private sealed interface Token {
            object OpenBracket : Token
            data class Symbol(val value: String) : Token
            data class StringToken(val value: String) : Token
            object CloseBracket : Token
        }

        private fun Char.isOpenQuote(): Boolean = this == '"' || this == '“'
        private fun Char.isCloseQuote(): Boolean = this == '"' || this == '”'

        private fun tokenize(tags: String): List<Token> {
            val inlineTags = tags.lines().joinToString("")

            val tokens = mutableListOf<Token>()
            var quoteOpened = false
            var symbol = StringBuilder()
            var string = StringBuilder()

            for (c in inlineTags) {
                if (c == '[') {
                    tokens.add(Token.OpenBracket)
                } else if (c == ']') {
                    tokens.add(Token.CloseBracket)
                } else if (c.isOpenQuote() && !quoteOpened) {
                    if (symbol.isNotEmpty()) {
                        tokens.add(Token.Symbol(symbol.toString()))
                        symbol = StringBuilder()
                    }
                    quoteOpened = true
                } else if (c.isCloseQuote() && quoteOpened) {
                    if (string.isNotEmpty()) {
                        tokens.add(Token.StringToken(string.toString()))
                        string = StringBuilder()
                    }
                    quoteOpened = false
                } else {
                    if (c.isWhitespace() && !quoteOpened) {
                        if (symbol.isNotEmpty()) {
                            tokens.add(Token.Symbol(symbol.toString()))
                            symbol = StringBuilder()
                        }
                    } else if (quoteOpened) {
                        string.append(c)
                    } else {
                        if (c.isLetter() || c.isDigit() || c == '_') {
                            symbol.append(c)
                        } else {
                            throw Error.UnexpectedTagCharacter(c.toString())
                        }
                    }
                }
            }

            return tokens
        }

        private fun parse(tags: String): Map<String, String> {
            val tokens = tokenize(tags)

            if (tokens.size % 4 != 0) throw Error.InvalidTagFormat

            val parsedTags = mutableListOf<Pair<String, String>>()

            for (i in tokens.indices step 4) {
                val t0 = tokens[i]
                val t1 = tokens[i + 1]
                val t2 = tokens[i + 2]
                val t3 = tokens[i + 3]

                if (t0 !is Token.OpenBracket || t3 !is Token.CloseBracket) throw Error.MismatchedTagBrackets
                if (t1 !is Token.Symbol) throw Error.TagSymbolNotFound
                if (t2 !is Token.StringToken) throw Error.TagStringNotFound

                parsedTags.add(t1.value to t2.value)
            }

            val result = LinkedHashMap<String, String>()
            for ((key, value) in parsedTags) {
                if (!result.containsKey(key)) result[key] = value
            }
            return result
        }
    }

    // MARK: - MoveText

    /** Parses PGN movetext. */
    private object MoveTextParser {

        fun game(moveText: String, startingPosition: Position): Game {
            val tokens = tokenize(moveText)
            return parse(tokens, startingPosition)
        }

        private sealed interface Token {
            data class Number(val value: String) : Token
            data class San(val value: String) : Token
            data class Annotation(val value: String) : Token
            data class Comment(val value: String) : Token
            object VariationStart : Token
            object VariationEnd : Token
            data class ResultToken(val value: String) : Token
        }

        private enum class TokenType {
            none, number, san, annotation, variationStart, variationEnd, result, comment;

            fun isValid(c: Char): Boolean = when (this) {
                none, comment -> false
                number -> isNumber(c)
                san -> isSAN(c)
                annotation -> isAnnotation(c)
                variationStart -> isVariationStart(c)
                variationEnd -> isVariationEnd(c)
                result -> isResult(c)
            }

            fun convert(text: String): Token? = when (this) {
                none -> null
                number -> Token.Number(text.trim())
                san -> Token.San(text.trim())
                annotation -> Token.Annotation(text.trim())
                comment -> Token.Comment(text.trim())
                variationStart -> Token.VariationStart
                variationEnd -> Token.VariationEnd
                result -> Token.ResultToken(text.trim())
            }

            companion object {
                fun isNumber(c: Char): Boolean = c.isDigit() || c == '.'
                fun isSAN(c: Char): Boolean = c.isLetter() || c.isDigit() || c in charArrayOf('x', '+', '#', '=', 'O', 'o', '0', '-')
                fun isAnnotation(c: Char): Boolean = c.isDigit() || c in charArrayOf('$', '?', '!', '□')
                fun isVariationStart(c: Char): Boolean = c == '('
                fun isVariationEnd(c: Char): Boolean = c == ')'
                fun isResult(c: Char): Boolean = c in charArrayOf('1', '2', '/', '-', '0', '*', '½')

                fun match(c: Char): TokenType = when {
                    isNumber(c) -> number
                    isSAN(c) -> san
                    isAnnotation(c) -> annotation
                    isVariationStart(c) -> variationStart
                    isVariationEnd(c) -> variationEnd
                    isResult(c) -> result
                    else -> none
                }
            }
        }

        private fun tokenize(moveText: String): List<Token> {
            var inlineMoveText = moveText.lines().joinToString("")
            var resultToken: Token? = null

            val moveWords = inlineMoveText.split(Regex("\\s+")).toMutableList()
            val resultMove = if (moveWords.isNotEmpty()) moveWords.removeAt(moveWords.size - 1) else null

            if (resultMove != null) {
                var isValidResult = true
                for (c in resultMove) {
                    isValidResult = TokenType.isResult(c)
                    if (!isValidResult) break
                }

                if (isValidResult) {
                    val token = TokenType.result.convert(resultMove)
                    if (token != null) {
                        resultToken = token
                        inlineMoveText = moveWords.joinToString(" ")
                    }
                }
            }

            val tokens = mutableListOf<Token>()
            var currentTokenType = TokenType.none
            var currentToken = StringBuilder()

            for (c in inlineMoveText) {
                if (c == '{') {
                    currentTokenType = TokenType.comment
                } else if (c == '}') {
                    if (currentTokenType != TokenType.comment) {
                        throw Error.UnpairedCommentDelimiter
                    } else {
                        if (currentToken.isNotEmpty()) {
                            currentTokenType.convert(currentToken.toString())?.let { tokens.add(it) }
                        }
                        currentTokenType = TokenType.none
                        currentToken = StringBuilder()
                    }
                } else if (currentTokenType == TokenType.comment || currentTokenType.isValid(c)) {
                    currentToken.append(c)
                } else {
                    if (currentToken.isNotEmpty()) {
                        currentTokenType.convert(currentToken.toString())?.let { tokens.add(it) }
                    }
                    currentTokenType = TokenType.match(c)
                    currentToken = StringBuilder().append(c)
                }
            }

            if (currentToken.isNotEmpty()) {
                currentTokenType.convert(currentToken.toString())?.let { tokens.add(it) }
            }

            if (resultToken != null) tokens.add(resultToken)

            return tokens
        }

        private fun parse(tokens: List<Token>, startingWith: Position): Game {
            val game = Game(startingWith = startingWith)
            val iterator = tokens.iterator()

            if (!iterator.hasNext()) throw Error.UnexpectedMoveTextToken
            val firstToken = iterator.next()

            var currentMoveIndex: MoveTree.Index

            when (firstToken) {
                is Token.Number -> {
                    val n = firstToken.value.takeWhile { it != '.' }.toIntOrNull()
                        ?: throw Error.UnexpectedMoveTextToken

                    currentMoveIndex = if (firstToken.value.count { it == '.' } >= 3) {
                        MoveTree.Index(n, Piece.Color.black).previous
                    } else {
                        MoveTree.Index(n, Piece.Color.white).previous
                    }
                }

                is Token.San -> {
                    currentMoveIndex = if (startingWith.sideToMove == Piece.Color.white) MoveTree.Index.minimum else MoveTree.Index.minimum.next
                    val position = game.positions[currentMoveIndex]

                    if (position != null) {
                        val move = SANParser.parse(firstToken.value, position) ?: throw Error.InvalidMove(firstToken.value)
                        currentMoveIndex = game.make(move, currentMoveIndex)
                    }
                }

                else -> throw Error.UnexpectedMoveTextToken
            }

            val variationStack = ArrayDeque<MoveTree.Index>()

            while (iterator.hasNext()) {
                when (val token = iterator.next()) {
                    is Token.Number, is Token.ResultToken -> {}

                    is Token.San -> {
                        val position = game.positions[currentMoveIndex]
                        val move = position?.let { SANParser.parse(token.value, it) }

                        if (move != null) {
                            currentMoveIndex = game.make(move, currentMoveIndex)
                        } else {
                            throw Error.InvalidMove(token.value)
                        }
                    }

                    is Token.Annotation -> {
                        val numericPositionMatch = Pattern.numericPosition.find(token.value)
                        if (numericPositionMatch != null) {
                            val positionAssessment = Position.Assessment.fromRawValue(numericPositionMatch.value)
                            if (positionAssessment != null) {
                                game.annotate(positionAt = currentMoveIndex, assessment = positionAssessment)
                                continue
                            }
                        }

                        val traditionalMatch = Pattern.traditional.find(token.value)
                        val numericMoveMatch = Pattern.numericMove.find(token.value)

                        val moveAssessment = when {
                            traditionalMatch != null -> Move.Assessment(traditionalMatch.value)
                            numericMoveMatch != null -> Move.Assessment.fromRawValue(numericMoveMatch.value)
                            else -> null
                        } ?: throw Error.InvalidAnnotation(token.value)

                        game.annotate(moveAt = currentMoveIndex, assessment = moveAssessment)
                    }

                    is Token.Comment -> game.annotate(moveAt = currentMoveIndex, comment = token.value)

                    is Token.VariationStart -> {
                        variationStack.addLast(currentMoveIndex)
                        currentMoveIndex = currentMoveIndex.previous
                    }

                    is Token.VariationEnd -> {
                        currentMoveIndex = if (variationStack.isNotEmpty()) {
                            variationStack.removeLast()
                        } else {
                            throw Error.UnpairedVariationDelimiter
                        }
                    }
                }
            }

            return game
        }

        private object Pattern {
            /** Numeric Annotation Glyphs for moves, e.g. `$1`, `$2`, etc. */
            val numericMove = Regex("^\\$\\d$")

            /** Numeric Annotation Glyphs for positions, e.g. `$10`, `$11`, etc. */
            val numericPosition = Regex("^\\$\\d{2,3}$")

            /** Traditional suffix annotations, e.g. `!!`, `?!`, `□`, etc. */
            val traditional = Regex("^[!?□]{1,2}$")
        }
    }
}
