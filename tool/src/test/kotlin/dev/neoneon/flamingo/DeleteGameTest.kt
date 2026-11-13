package dev.neoneon.flamingo

import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deleting a finished online game: when the game screen offers it, and what its confirmation says.
 * The request itself is signed with the AndroidKeyStore key, so it is checked on a device.
 */
class DeleteGameTest {

    private val checkmate = GameOutcome.Checkmate(Piece.Color.white)

    private fun seated(
        outcome: GameOutcome?,
        isLoading: Boolean = false,
        isArchived: Boolean = false,
    ) = GameViewViewModel.State(
        position = Position.standard,
        localColor = Piece.Color.white,
        isLoading = isLoading,
        playerId = "96596872-5fe3-4574-8684-aca1047afa23",
        whitePlayerId = "96596872-5FE3-4574-8684-ACA1047AFA23",
        blackPlayerId = "AB98F326-AA39-4F83-8E15-EA8ECABBFC05",
        outcome = outcome,
        isArchived = isArchived,
    )

    // MARK: When it is offered

    @Test
    fun aFinishedGameWeSitInCanBeDeleted() {
        assertTrue(seated(outcome = checkmate).canDelete)
        assertTrue(seated(outcome = GameOutcome.Resigned(Piece.Color.black)).canDelete)
        assertTrue(seated(outcome = GameOutcome.Drawn(Board.State.DrawReason.agreement)).canDelete)
    }

    /** Archiving doesn't keep a game from being deleted: the archive is a place to delete from. */
    @Test
    fun anArchivedGameCanBeDeleted() {
        assertTrue(seated(outcome = checkmate, isArchived = true).canDelete)
    }

    @Test
    fun aGameStillBeingPlayedCannotBeDeleted() {
        assertFalse(seated(outcome = null).canDelete)
    }

    @Test
    fun aDrawTheServerDoesNotRecordCannotBeDeleted() {
        // It stays `active` on the server, which would refuse the request — as it refuses archiving.
        val repetition = GameOutcome.Drawn(Board.State.DrawReason.repetition)
        assertFalse(seated(outcome = repetition).canDelete)
    }

    @Test
    fun nothingCanBeDeletedBeforeTheRecordHasLoaded() {
        assertFalse(seated(outcome = checkmate, isLoading = true).canDelete)
    }

    @Test
    fun aGameWeDoNotSitInCannotBeDeleted() {
        assertFalse(seated(outcome = checkmate).copy(playerId = "someone-else").canDelete)
    }

    /** The server refuses exactly the same games for both, so the two rows grey out together. */
    @Test
    fun deletingIsOfferedForExactlyTheGamesArchivingIs() {
        val states = listOf(
            seated(outcome = checkmate),
            seated(outcome = checkmate, isArchived = true),
            seated(outcome = null),
            seated(outcome = GameOutcome.Drawn(Board.State.DrawReason.fiftyMoves)),
            seated(outcome = checkmate, isLoading = true),
            seated(outcome = checkmate).copy(playerId = null),
        )
        states.forEach { assertEquals(it.canArchive, it.canDelete, "for $it") }
    }

    // MARK: The confirmation

    /** In the archive, Delete sits beside Unarchive, so the message says first that it isn't that. */
    @Test
    fun fromTheArchiveItSaysDeletingIsNotUnarchiving() {
        val message = deleteConfirmation(isArchived = true)

        assertEquals("Deleting isn't unarchiving.", message.lines().first())
        assertTrue("leaves your archive" in message)
    }

    /** From the games list, it says the game won't go to the archive either. */
    @Test
    fun fromTheGamesListItSaysTheArchiveIsSkipped() {
        val message = deleteConfirmation(isArchived = false)

        assertTrue("leaves your games" in message)
        assertTrue("won't go to your archive" in message)
        assertFalse("unarchiving" in message)
    }

    /** No undo, and only this seat's lists change — the opponent's don't, whatever they did. */
    @Test
    fun bothSayItCannotBeUndoneAndOnlyYourListsChange() {
        for (isArchived in listOf(true, false)) {
            val message = deleteConfirmation(isArchived)
            assertTrue("can't be brought back" in message, message)
            assertEquals("Only your lists change.", message.lines().last())
        }
    }

    /** Hand-broken to fit the Light Phone's width at the copy size, like the other confirmations. */
    @Test
    fun everyLineFitsTheScreen() {
        for (isArchived in listOf(true, false)) {
            deleteConfirmation(isArchived).lines().forEach { line ->
                assertTrue(line.length <= 30, "\"$line\" is ${line.length} characters")
            }
        }
    }
}
