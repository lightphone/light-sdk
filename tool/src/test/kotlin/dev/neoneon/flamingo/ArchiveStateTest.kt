package dev.neoneon.flamingo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the archive screen is a spinner, when it is one "No archived games", and when it is a list. */
class ArchiveStateTest {

    private val localGame = ArchivedHotSeatGame(
        game = HotSeatGame(id = "local", startedAtMillis = 7, moves = listOf("f2f3", "e7e5", "g2g4", "d8h4")),
        outcome = ArchivedOutcome.BlackWins,
        archivedAtMillis = 42,
    )

    private val onlineGame = Game(
        id = "online", fen = "f", status = "checkmate", createdAt = "x", updatedAt = "y",
        whitePlayerID = "us", blackPlayerID = "them", archivedByWhite = true,
    )

    private fun loaded(vararg games: Game) = OnlineArchive.Loaded(games.toList(), playerId = "us")

    @Test
    fun itLoadsUntilThePhonesArchiveHasBeenRead() {
        assertTrue(ArchiveState(online = loaded(onlineGame), localGames = null).isLoading)
    }

    @Test
    fun withNoLocalGamesItWaitsForTheServer() {
        assertTrue(ArchiveState(online = OnlineArchive.Loading, localGames = emptyList()).isLoading)
    }

    @Test
    fun localGamesShowWithoutWaitingForTheServer() {
        val state = ArchiveState(online = OnlineArchive.Loading, localGames = listOf(localGame))
        assertFalse(state.isLoading)
        assertFalse(state.isEmpty)
    }

    @Test
    fun itIsEmptyOnlyOnceBothHalvesHaveAnsweredWithNothing() {
        assertTrue(ArchiveState(online = loaded(), localGames = emptyList()).isEmpty)
        assertFalse(ArchiveState(online = loaded(onlineGame), localGames = emptyList()).isEmpty)
        assertFalse(ArchiveState(online = loaded(), localGames = listOf(localGame)).isEmpty)
    }

    @Test
    fun aServerThatCouldNotAnswerIsNotAnEmptyArchive() {
        // "No archived games" would be a claim about the server's list that nobody has read.
        assertFalse(ArchiveState(online = OnlineArchive.Failed("Couldn't load"), localGames = emptyList()).isEmpty)
        assertFalse(ArchiveState(online = OnlineArchive.Failed("Couldn't load"), localGames = emptyList()).isLoading)
    }
}
