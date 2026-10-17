package dev.neoneon.flamingo

import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Piece
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotSeatArchiveTest {

    private fun archived(id: String, outcome: ArchivedOutcome = ArchivedOutcome.Draw, at: Long = 42) =
        ArchivedHotSeatGame(
            game = HotSeatGame(id = id, startedAtMillis = 7, moves = listOf("e2e4", "e7e5")),
            outcome = outcome,
            archivedAtMillis = at,
        )

    // MARK: Storing

    @Test
    fun roundTrips() {
        val games = listOf(archived("b", ArchivedOutcome.WhiteWins), archived("a", ArchivedOutcome.BlackWins))
        assertEquals(games, decodeHotSeatArchive(encodeHotSeatArchive(games)))
    }

    @Test
    fun roundTripsAnEmptyArchive() {
        assertEquals(emptyList(), decodeHotSeatArchive(encodeHotSeatArchive(emptyList())))
    }

    @Test
    fun outcomesAreStoredInTheWordsIosUses() {
        val encoded = encodeHotSeatArchive(
            ArchivedOutcome.entries.mapIndexed { index, outcome -> archived("$index", outcome) },
        )
        assertTrue("\"whiteWins\"" in encoded)
        assertTrue("\"blackWins\"" in encoded)
        assertTrue("\"draw\"" in encoded)
    }

    @Test
    fun theIdIsTheGamesAndIsNotStoredTwice() {
        assertEquals("a", archived("a").id)
        assertTrue("\"id\":\"a\"" in encodeHotSeatArchive(listOf(archived("a"))))
        assertEquals(1, Regex("\"id\"").findAll(encodeHotSeatArchive(listOf(archived("a")))).count())
    }

    @Test
    fun nothingStoredIsNoArchive() {
        assertNull(decodeHotSeatArchive(null))
    }

    @Test
    fun garbageIsNoArchive() {
        assertNull(decodeHotSeatArchive("not json"))
        assertNull(decodeHotSeatArchive(""))
    }

    @Test
    fun anotherShapeIsNoArchive() {
        assertNull(decodeHotSeatArchive("""{"games": []}"""))
        assertNull(
            decodeHotSeatArchive(
                """[{"game":{"id":"a","startedAtMillis":7,"moves":[]},"outcome":"whiteWon","archivedAtMillis":42}]""",
            ),
        )
    }

    @Test
    fun unknownKeysAreIgnored() {
        val decoded = decodeHotSeatArchive(
            """[{"game":{"id":"a","startedAtMillis":7,"moves":["e2e4","e7e5"]},"outcome":"draw",""" +
                """"archivedAtMillis":42,"later":true}]""",
        )
        assertEquals(listOf(archived("a")), decoded)
    }

    // MARK: Adding and removing

    @Test
    fun addingPutsTheGameFirst() {
        val archive = emptyList<ArchivedHotSeatGame>().adding(archived("a")).adding(archived("b"))
        assertEquals(listOf("b", "a"), archive.map { it.id })
    }

    @Test
    fun addingTheSameGameAgainReplacesItAndMovesItFirst() {
        val archive = listOf(archived("b"), archived("a", at = 1)).adding(archived("a", at = 2))
        assertEquals(listOf("a", "b"), archive.map { it.id })
        assertEquals(2, archive.first().archivedAtMillis)
    }

    @Test
    fun removingDropsOnlyThatGame() {
        val archive = listOf(archived("c"), archived("b"), archived("a")).removing("b")
        assertEquals(listOf("c", "a"), archive.map { it.id })
    }

    @Test
    fun removingAGameThatIsNotThereChangesNothing() {
        val archive = listOf(archived("a"))
        assertEquals(archive, archive.removing("z"))
    }

    // MARK: The outcome

    @Test
    fun checkmateIsAWinForTheMatingSide() {
        assertEquals(ArchivedOutcome.WhiteWins, GameOutcome.Checkmate(Piece.Color.white).archived())
        assertEquals(ArchivedOutcome.BlackWins, GameOutcome.Checkmate(Piece.Color.black).archived())
    }

    @Test
    fun everyDrawIsADraw() {
        assertEquals(ArchivedOutcome.Draw, GameOutcome.Stalemate.archived())
        for (reason in Board.State.DrawReason.entries) {
            assertEquals(ArchivedOutcome.Draw, GameOutcome.Drawn(reason).archived())
        }
    }

    @Test
    fun aResignationIsAWinForTheOtherSide() {
        assertEquals(ArchivedOutcome.BlackWins, GameOutcome.Resigned(Piece.Color.white).archived())
        assertEquals(ArchivedOutcome.WhiteWins, GameOutcome.Resigned(Piece.Color.black).archived())
    }

    @Test
    fun outcomesReadAsOnIos() {
        assertEquals("White won", ArchivedOutcome.WhiteWins.label)
        assertEquals("Black won", ArchivedOutcome.BlackWins.label)
        assertEquals("Draw", ArchivedOutcome.Draw.label)
    }

    @Test
    fun theSavingTimeReadsLikeTheGamesList() {
        val saved = archived("a", at = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli())
        assertEquals("2 days ago", saved.archivedAgo(now = Instant.parse("2026-10-17T12:00:00Z")))
    }
}
