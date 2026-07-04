package dev.neoneon.chesskit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PGNParserTests {

    @Test
    fun gameFromEmptyPGN() {
        assertEquals(Game(startingWith = Position.standard), PGNParser.parse(""))
    }

    @Test
    fun gameFromPGN() {
        val game = PGNParser.parse(fischerSpasskyPGN)
        val gameFromPGN = Game(fischerSpasskyPGN)

        assertEquals(game, gameFromPGN)
    }

    @Test
    fun pgnFromGame() {
        val game = Game()
        game.make(listOf("e4", "e5", "Nf3", "Nc6", "Bc4"), MoveTree.Index.minimum)
        assertEquals("1. e4 e5 2. Nf3 Nc6 3. Bc4", PGNParser.convert(game))
    }

    @Test
    fun pgnFromEmptyGame() {
        assertEquals("", PGNParser.convert(Game()))
    }

    // MARK: Tags

    @Test
    fun tagParsing() {
        val game = PGNParser.parse(fischerSpasskyPGN)
        assertEquals("F/S Return Match", game.tags.event)
        assertEquals("Belgrade, Serbia JUG", game.tags.site)
        assertEquals("1992.11.04", game.tags.date)
        assertEquals("29", game.tags.round)
        assertEquals("Fischer, Robert J.", game.tags.white)
        assertEquals("Spassky, Boris V.", game.tags.black)
        assertEquals("1/2-1/2", game.tags.result)
        assertEquals("Mr. Annotator", game.tags.annotator)
        assertEquals("85", game.tags.plyCount)
        assertEquals("?", game.tags.timeControl)
        assertEquals("??:??:??", game.tags.time)
        assertEquals("normal", game.tags.termination)
        assertEquals("OTB", game.tags.mode)
    }

    @Test
    fun tagResultWinParsing() {
        val game = PGNParser.parse(byrneFischerPGN)
        assertEquals("0-1", game.tags.result)
    }

    @Test
    fun tagParsingIrregularWhitespace() {
        val game = PGNParser.parse(
            """
            [Tag1 "A"     ]
        [      Tag2   "B"]
            [ Tag3"C"      ]

        1. e4 e5
            """.trimIndent(),
        )

        assertEquals("A", game.tags.other["Tag1"])
        assertEquals("B", game.tags.other["Tag2"])
        assertEquals("C", game.tags.other["Tag3"])
    }

    @Test
    fun customTagParsing() {
        // invalid pair
        assertFailsWith<PGNParser.Error.InvalidTagFormat> {
            PGNParser.parse("[a]\n\n1. e4 e5")
        }

        // custom tag
        val g2 = PGNParser.parse("[Custom_Tag \"Value\"]\n\n1. e4 e5")
        assertEquals("Value", g2.tags.other["Custom_Tag"])

        // duplicate tags
        val g3 = PGNParser.parse("[CustomTag \"Value\"] [CustomTag \"Value2\"]\n\n1. e4 e5")
        assertEquals("Value", g3.tags.other["CustomTag"])
    }

    // MARK: MoveText

    @Test
    fun moveTextParsing() {
        val game = PGNParser.parse(fischerSpasskyPGN)

        // starting position + 85 ply
        assertEquals(86, game.positions.keys.size)

        assertEquals(Move.Assessment.blunder, game.moves[MoveTree.Index(1, Piece.Color.white)]?.assessment)
        assertEquals(Move.Assessment.brilliant, game.moves[MoveTree.Index(1, Piece.Color.black)]?.assessment)
        assertEquals("This opening is called the Ruy Lopez.", game.moves[MoveTree.Index(3, Piece.Color.black)]?.comment)
        assertEquals("test comment", game.moves[MoveTree.Index(4, Piece.Color.white)]?.comment)
        assertEquals(Position.Assessment.blackHasDecisiveCounterplay, game.positions[MoveTree.Index(7, Piece.Color.black)]?.assessment)
        assertEquals(Square.d4, game.moves[MoveTree.Index(10, Piece.Color.white)]?.end)
        assertEquals(Piece.Kind.queen, game.moves[MoveTree.Index(18, Piece.Color.black)]?.piece?.kind)
        assertEquals(Square.e7, game.moves[MoveTree.Index(18, Piece.Color.black)]?.end)
        assertEquals(Move.CheckState.check, game.moves[MoveTree.Index(36, Piece.Color.white)]?.checkState)
    }

    @Test
    fun numberlessMoveTextParsing() {
        val game = PGNParser.parse("e4 e5 Nf3")
        assertEquals("e4", game.moves[MoveTree.Index(1, Piece.Color.white)]?.san)
        assertEquals("e5", game.moves[MoveTree.Index(1, Piece.Color.black)]?.san)
        assertEquals("Nf3", game.moves[MoveTree.Index(2, Piece.Color.white)]?.san)
    }

    @Test
    fun startWithBlack() {
        val g1 = PGNParser.parse("[FEN \"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1\"][SetUp \"1\"]\n\n1... e5 2. Nf3 Nc6")
        assertEquals(null, g1.moves[MoveTree.Index(1, Piece.Color.white)])
        assertEquals("e5", g1.moves[MoveTree.Index(1, Piece.Color.black)]?.san)
        assertEquals("Nf3", g1.moves[MoveTree.Index(2, Piece.Color.white)]?.san)
        assertEquals("Nc6", g1.moves[MoveTree.Index(2, Piece.Color.black)]?.san)

        val g2 = PGNParser.parse("[FEN \"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1\"][SetUp \"1\"]\n\ne5 Nf3 Nc6")
        assertEquals(null, g2.moves[MoveTree.Index(1, Piece.Color.white)])
        assertEquals("e5", g2.moves[MoveTree.Index(1, Piece.Color.black)]?.san)
        assertEquals("Nf3", g2.moves[MoveTree.Index(2, Piece.Color.white)]?.san)
        assertEquals("Nc6", g2.moves[MoveTree.Index(2, Piece.Color.black)]?.san)
    }

    @Test
    fun variationParsing() {
        val game = PGNParser.parse("1. e4 e5 (1... c6)")

        // starting position + 3 ply
        assertEquals(4, game.positions.keys.size)

        assertEquals("e4", game.moves[MoveTree.Index(1, Piece.Color.white)]?.san)
        assertEquals("e5", game.moves[MoveTree.Index(1, Piece.Color.black)]?.san)
        assertEquals("c6", game.moves[MoveTree.Index(1, Piece.Color.black, 1)]?.san)
    }

    // MARK: Errors

    @Test
    fun tooManyLineBreaksError() {
        assertFailsWith<PGNParser.Error.TooManyLineBreaks> {
            PGNParser.parse("[Round \"1\"]\n\n1.e4 e5\n\n2. Nf3 Nc6")
        }
    }

    @Test
    fun invalidSetUpOrFENError() {
        assertFailsWith<PGNParser.Error.InvalidSetUpOrFEN> {
            PGNParser.parse("[SetUp \"2\"]\n\n1. e4 e5")
        }

        assertFailsWith<PGNParser.Error.InvalidSetUpOrFEN> {
            PGNParser.parse("[FEN \"invalid\"] [SetUp \"1\"]\n\n1. e4 e5")
        }
    }

    @Test
    fun unexpectedCharacterError() {
        val e = assertFailsWith<PGNParser.Error.UnexpectedTagCharacter> {
            PGNParser.parse("[Tag% \"Value\"]\n\n1. e4 e5")
        }
        assertEquals("%", e.character)
    }

    @Test
    fun tagTokenErrors() {
        assertFailsWith<PGNParser.Error.MismatchedTagBrackets> {
            PGNParser.parse("][Tag \"Value\"\n\n1.e4 e5")
        }

        assertFailsWith<PGNParser.Error.TagSymbolNotFound> {
            PGNParser.parse("[\"Tag\" \"Value\"]\n\n1.e4 e5")
        }

        assertFailsWith<PGNParser.Error.TagStringNotFound> {
            PGNParser.parse("[Tag Value ]\n\n1.e4 e5")
        }
    }

    @Test
    fun unexpectedMoveTextTokenError() {
        assertFailsWith<PGNParser.Error.UnexpectedMoveTextToken> {
            PGNParser.parse("\$0 1. e4 abc123 2. Nc6")
        }
    }

    @Test
    fun invalidMoveError() {
        val e1 = assertFailsWith<PGNParser.Error.InvalidMove> {
            PGNParser.parse("1. e4 abc123 2. Nc6")
        }
        assertEquals("abc123", e1.san)

        val e2 = assertFailsWith<PGNParser.Error.InvalidMove> {
            PGNParser.parse("abc123 e5 Nc6")
        }
        assertEquals("abc123", e2.san)
    }

    @Test
    fun invalidAnnotationError() {
        val e1 = assertFailsWith<PGNParser.Error.InvalidAnnotation> {
            PGNParser.parse("1. e4 e5 \$\$0 2. Nc6")
        }
        assertEquals("\$\$0", e1.annotation)

        val e2 = assertFailsWith<PGNParser.Error.InvalidAnnotation> {
            PGNParser.parse("1. e4 e5 \$999 2. Nc6")
        }
        assertEquals("\$999", e2.annotation)

        val e3 = assertFailsWith<PGNParser.Error.InvalidAnnotation> {
            PGNParser.parse("1. e4 e5!!! 2. Nc6")
        }
        assertEquals("!!!", e3.annotation)

        val e4 = assertFailsWith<PGNParser.Error.InvalidAnnotation> {
            PGNParser.parse("1. e4 e5□□ 2. Nc6")
        }
        assertEquals("□□", e4.annotation)
    }

    @Test
    fun unpairedCommentDelimiterError() {
        assertFailsWith<PGNParser.Error.UnpairedCommentDelimiter> {
            PGNParser.parse("1. e4 e5 2. c6 } { this is a comment }")
        }
    }

    @Test
    fun moveVariationError() {
        assertFailsWith<PGNParser.Error.UnpairedVariationDelimiter> {
            PGNParser.parse("1. e4 e5 )1... c6)")
        }
    }
}
