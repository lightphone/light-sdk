package dev.neoneon.chesskit

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameTests {

    private lateinit var game: Game

    // MARK: Test Indices

    private val nf3Index = MoveTree.Index(2, Piece.Color.white, 0)
    private val bc4Index = MoveTree.Index(3, Piece.Color.white, 0)
    private val nc3Index = MoveTree.Index(2, Piece.Color.white, 1)
    private val nf6Index = MoveTree.Index(2, Piece.Color.black, 1)
    private val nc6Index = MoveTree.Index(2, Piece.Color.black, 2)
    private val nc6Index2 = MoveTree.Index(2, Piece.Color.black, 0)
    private val f5Index = MoveTree.Index(2, Piece.Color.black, 3)

    private val mockTags = Game.Tags(
        event = "Test Event",
        site = "Barrow, Alaska USA",
        date = "2000.01.01",
        round = "5",
        white = "Player One",
        black = "Player Two",
        result = "1-0",
        annotator = "Annotator",
        plyCount = "15",
        timeControl = "40/7200:3600",
        time = "12:00",
        termination = "abandoned",
        mode = "OTB",
        fen = Position.standard.fen,
        setUp = "1",
        other = mapOf(
            "TestKey1" to "Test Value 1",
            "TestKey2" to "Test Value 2",
        ),
    )

    @BeforeTest
    fun setup() {
        game = Game()
        game.tags = mockTags

        game.make(listOf("e4", "e5", "Nf3", "Nc6", "Bc4"), MoveTree.Index.minimum)

        // add 2. Nc3 ... variation to 2. Nf3
        game.make(listOf("Nc3", "Nf6", "Bc4"), nf3Index.previous)

        // add 2... Nc6 ... variation to 2... Nf6
        game.make(listOf("Nc6", "f4"), nf6Index.previous)

        // add another variation to 2... Nf6
        game.make(listOf("f5", "exf5"), nc6Index2.previous)

        // make repeat moves to test proper handling
        game.make("e4", MoveTree.Index.minimum)
        game.make("e5", MoveTree.Index.minimum.next)
        game.make(listOf("Nc3", "Nf6"), nf3Index.previous)
    }

    // MARK: Test Cases

    @Test
    fun startingPosition() {
        val game1 = Game(startingWith = Position.standard)
        assertEquals(MoveTree.Index(0, Piece.Color.black, 0), game1.startingIndex)
        assertEquals(Position.standard, game1.startingPosition)

        val fen = "r1bqkb1r/pp1ppppp/2n2n2/8/2B1P3/2N2N2/PP3PPP/R1BQK2R b KQkq - 4 6"
        val game2 = Game(startingWith = Position(fen)!!)
        assertEquals(MoveTree.Index(1, Piece.Color.white, 0), game2.startingIndex)
        assertEquals(Position(fen)!!, game2.startingPosition)

        game2.make("O-O", game2.startingIndex)
        assertEquals(MoveTree.Index(1, Piece.Color.black, 0), game2.moves.indexAfter(game2.moves.minimumIndex))
        assertEquals(Move("O-O", Position(fen)!!), game2.moves[MoveTree.Index(1, Piece.Color.black, 0)])
    }

    @Test
    fun validMoves() {
        assertFalse(game.moves.isEmpty)
        assertEquals("e4", game.moves[MoveTree.Index(1, Piece.Color.white)]?.san)
        assertEquals("e5", game.moves[MoveTree.Index(1, Piece.Color.black)]?.san)
        assertEquals("Nf3", game.moves[MoveTree.Index(2, Piece.Color.white)]?.san)
        assertEquals("Nc6", game.moves[MoveTree.Index(2, Piece.Color.black)]?.san)
        assertEquals("Bc4", game.moves[MoveTree.Index(3, Piece.Color.white)]?.san)
    }

    @Test
    fun invalidMoves() {
        val movesBefore = game.moves

        assertEquals(MoveTree.Index(10, Piece.Color.black), game.make("e1", MoveTree.Index(10, Piece.Color.black)))
        // test that `MoveTree` has not changed
        assertEquals(movesBefore, game.moves)

        assertEquals(
            MoveTree.Index(100, Piece.Color.white),
            game.make(Move("e4", Position.standard)!!, MoveTree.Index(100, Piece.Color.white)),
        )
    }

    @Test
    fun moveTree() {
        assertEquals("Nf3", game.moves[nf3Index]?.san)
        assertEquals("Nc3", game.moves[nc3Index]?.san)

        assertEquals("Nf6", game.moves[nf6Index]?.san)
        assertEquals("Nc6", game.moves[nc6Index]?.san)

        assertEquals(nc3Index, game.moves.indexBefore(nc6Index))

        assertEquals("Nc6", game.moves[nc6Index2]?.san)
        assertEquals("f5", game.moves[f5Index]?.san)

        assertEquals(nf3Index, game.moves.indexBefore(f5Index))

        assertEquals(MoveTree.Index.minimum, game.moves.indexBefore(MoveTree.Index.minimum.next))
        assertEquals(nf6Index, game.moves.indexAfter(nc3Index))
    }

    @Test
    fun moveAnnotation() {
        game.annotate(nc3Index, assessment = Move.Assessment.brilliant)
        game.annotate(f5Index, comment = "Comment test")

        val moveText = PGNParser.convert(game).split("\n").last()
        val expectedMoveText = "1. e4 e5 2. Nf3 (2. Nc3 \$3 Nf6 (2... Nc6 3. f4) 3. Bc4) Nc6 (2... f5 {Comment test} 3. exf5) 3. Bc4 1-0"

        assertEquals(expectedMoveText, moveText)
    }

    @Test
    fun positionAnnotation() {
        game.annotate(nc3Index, assessment = Position.Assessment.whiteHasCrushingAdvantage)
        game.annotate(bc4Index, assessment = Position.Assessment.whiteHasModerateTimeAdvantage)

        val moveText = PGNParser.convert(game).split("\n").last()
        val expectedMoveText = "1. e4 e5 2. Nf3 (2. Nc3 \$20 Nf6 (2... Nc6 3. f4) 3. Bc4) Nc6 (2... f5 3. exf5) 3. Bc4 \$32 1-0"

        assertEquals(expectedMoveText, moveText)
    }

    @Test
    fun moveHistory() {
        val f5History = game.moves.history(f5Index)
        val expectedF5History = listOf(
            MoveTree.Index(1, Piece.Color.white, 0),
            MoveTree.Index(1, Piece.Color.black, 0),
            MoveTree.Index(2, Piece.Color.white, 0),
            f5Index,
        )
        assertEquals(expectedF5History, f5History)

        val emptyHistory = game.moves.history(MoveTree.Index.minimum)
        assertEquals(listOf(MoveTree.Index(1, Piece.Color.white)), emptyHistory)
    }

    @Test
    fun moveFuture() {
        val f5Future = game.moves.future(f5Index)
        val expectedF5Future = listOf(MoveTree.Index(3, Piece.Color.white, 3))
        assertEquals(expectedF5Future, f5Future)

        val fullFuture = game.moves.future(MoveTree.Index.minimum)
        val expectedFullFuture = listOf(
            MoveTree.Index(1, Piece.Color.black, 0),
            MoveTree.Index(2, Piece.Color.white, 0),
            MoveTree.Index(2, Piece.Color.black, 0),
            MoveTree.Index(3, Piece.Color.white, 0),
        )
        assertEquals(expectedFullFuture, fullFuture)
    }

    @Test
    fun moveFullVariation() {
        val f5History = game.moves.history(f5Index)
        val f5Future = game.moves.future(f5Index)
        val f5Full = game.moves.fullVariation(f5Index)
        assertEquals(f5Full, f5History + f5Future)
    }

    @Test
    fun moveTreeEmptyPath() {
        assertTrue(game.moves.path(nc3Index, nc3Index).isEmpty())
    }

    @Test
    fun moveTreeInvalidPath() {
        assertTrue(
            game.moves
                .path(nc3Index, MoveTree.Index(100, Piece.Color.white))
                .isEmpty(),
        )
    }

    @Test
    fun moveTreeSimplePath() {
        // "1. e4 e5 2. Nf3 (2. Nc3 Nf6 (2... Nc6 3. f4) 3. Bc4) Nc6 (2... f5 3. exf5) 3. Bc4"
        val f4 = MoveTree.Index(3, Piece.Color.white, 2)
        val e5 = MoveTree.Index(1, Piece.Color.black, 0)

        // 3. f4 to 1. e5
        val path1 = game.moves.path(f4, e5)

        assertEquals(listOf(MoveTree.PathDirection.reverse, MoveTree.PathDirection.reverse, MoveTree.PathDirection.reverse), path1.map { it.direction })

        assertEquals(
            listOf(
                f4,
                MoveTree.Index(2, Piece.Color.black, 2),
                MoveTree.Index(2, Piece.Color.white, 1),
            ),
            path1.map { it.index },
        )

        // 1. e5 to 3. f4
        val path2 = game.moves.path(e5, f4)

        assertEquals(listOf(MoveTree.PathDirection.forward, MoveTree.PathDirection.forward, MoveTree.PathDirection.forward), path2.map { it.direction })

        assertEquals(
            listOf(
                MoveTree.Index(2, Piece.Color.white, 1),
                MoveTree.Index(2, Piece.Color.black, 2),
                f4,
            ),
            path2.map { it.index },
        )
    }

    @Test
    fun moveTreeComplexPath() {
        // "1. e4 e5 2. Nf3 (2. Nc3 Nf6 (2... Nc6 3. f4) 3. Bc4) Nc6 (2... f5 3. exf5) 3. Bc4"
        // 3. f4 to 3. Bc4
        val f4 = MoveTree.Index(3, Piece.Color.white, 2)
        val bC4 = MoveTree.Index(3, Piece.Color.white, 0)
        val path = game.moves.path(f4, bC4)

        assertEquals(
            listOf(
                MoveTree.PathDirection.reverse,
                MoveTree.PathDirection.reverse,
                MoveTree.PathDirection.reverse,
                MoveTree.PathDirection.forward,
                MoveTree.PathDirection.forward,
                MoveTree.PathDirection.forward,
                MoveTree.PathDirection.forward,
            ),
            path.map { it.direction },
        )

        assertEquals(
            listOf(
                f4,
                MoveTree.Index(2, Piece.Color.black, 2),
                MoveTree.Index(2, Piece.Color.white, 1),
                MoveTree.Index(1, Piece.Color.black, 0),
                MoveTree.Index(2, Piece.Color.white, 0),
                MoveTree.Index(2, Piece.Color.black, 0),
                bC4,
            ),
            path.map { it.index },
        )
    }

    @Test
    fun pgn() {
        val pgn = """
            [Event "Test Event"]
            [Site "Barrow, Alaska USA"]
            [Date "2000.01.01"]
            [Round "5"]
            [White "Player One"]
            [Black "Player Two"]
            [Result "1-0"]
            [Annotator "Annotator"]
            [PlyCount "15"]
            [TimeControl "40/7200:3600"]
            [Time "12:00"]
            [Termination "abandoned"]
            [Mode "OTB"]
            [FEN "${Position.standard.fen}"]
            [SetUp "1"]
            [TestKey1 "Test Value 1"]
            [TestKey2 "Test Value 2"]

            1. e4 e5 2. Nf3 (2. Nc3 Nf6 (2... Nc6 3. f4) 3. Bc4) Nc6 (2... f5 3. exf5) 3. Bc4 1-0
        """.trimIndent()

        assertEquals(pgn, game.pgn)
    }

    @Test
    fun validTagPairs() {
        val pgn = """
            [Event "Test Event"]
            [Site "Barrow, Alaska USA"]
            [Date "2000.01.01"]
            [Round "5"]
            [White "Player One"]
            [Black "Player Two"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 (2. Nc3 Nf6 (2... Nc6 3. f4) 3. Bc4) Nc6 (2... f5 3. exf5) 3. Bc4
        """.trimIndent()

        val parsedGame = Game(pgn)
        assertTrue(parsedGame.tags.isValid)
    }

    @Test
    fun invalidTagPairs() {
        val pgn = """
            [Event "Test Event"]

            1. e4 e5 2. Nf3 (2. Nc3 Nf6 (2... Nc6 3. f4) 3. Bc4) Nc6 (2... f5 3. exf5) 3. Bc4
        """.trimIndent()

        val parsedGame = Game(pgn)
        assertFalse(parsedGame.tags.isValid)
        assertTrue(Game.Tag("Site", parsedGame.tags.site).pgn.isEmpty())
    }

    @Test
    fun gameWithPromotion() {
        game.make(
            listOf("f5", "d4", "fxe4", "d5", "exf3", "d6", "fxg2", "dxc7", "gxh1=Q+", "Ke2", "Nb4", "cxd8=Q+"),
            bc4Index,
        )
        assertTrue(game.moves.indices.mapNotNull { game.moves[it]?.san }.contains("gxh1=Q+"))
    }
}
