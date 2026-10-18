package dev.neoneon.flamingo

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlamingoModelsTest {

    // Same config as FlamingoApi's client: unknown keys (e.g. the newer `gameOrigin`) are ignored.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesWaitingGameWithAbsentWhitePlayerAndUnknownGameOrigin() {
        // Real GET /flamingo/games/{id} body for a black-initiator waiting game: `whitePlayerID`
        // is omitted entirely (Swift encodes nil as absent), `gameOrigin` is an unknown key,
        // and there are no moves yet. Must decode with whitePlayerID == null.
        val body = """{"game":{"createdAt":"2026-07-23T16:01:23Z","status":"waitingForOpponent",""" +
            """"gameOrigin":"lightPhone","id":"2A787C32","blackPlayerID":"AB98F326",""" +
            """"fen":"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",""" +
            """"updatedAt":"2026-07-23T16:02:01Z"},"moves":[]}"""

        val detail = json.decodeFromString<GameDetail>(body)

        assertNull(detail.game.whitePlayerID)
        assertEquals("AB98F326", detail.game.blackPlayerID)
        assertEquals("waitingForOpponent", detail.game.status)
        assertTrue(detail.moves.isEmpty())
    }

    @Test
    fun decodesActiveGameWithBothSeats() {
        val body = """{"game":{"createdAt":"x","status":"active","gameOrigin":"lightPhone",""" +
            """"id":"2A787C32","whitePlayerID":"96596872","blackPlayerID":"AB98F326",""" +
            """"fen":"f","updatedAt":"y"},"moves":[]}"""

        val detail = json.decodeFromString<GameDetail>(body)

        assertEquals("96596872", detail.game.whitePlayerID)
        assertEquals("AB98F326", detail.game.blackPlayerID)
        assertEquals("active", detail.game.status)
    }

    @Test
    fun decodesTheArchiveFlags() {
        val body = """{"game":{"id":"g","fen":"f","status":"checkmate","createdAt":"x","updatedAt":"y",""" +
            """"whitePlayerID":"W","blackPlayerID":"B","archivedByWhite":true,"archivedByBlack":false}}"""

        val game = json.decodeFromString<ArchiveResponse>(body).game

        assertTrue(game.archivedByWhite)
        assertFalse(game.archivedByBlack)
    }

    @Test
    fun aGameWithoutArchiveFlagsIsArchivedByNobody() {
        val body = """{"id":"g","fen":"f","status":"checkmate","createdAt":"x","updatedAt":"y",""" +
            """"whitePlayerID":"W","blackPlayerID":"B"}"""

        val game = json.decodeFromString<Game>(body)

        assertFalse(game.isArchivedBy("W"))
        assertFalse(game.isArchivedBy("B"))
    }

    @Test
    fun archivingIsReadFromOurOwnSeat() {
        val game = Game(
            id = "g", fen = "f", status = "checkmate", createdAt = "x", updatedAt = "y",
            whitePlayerID = "96596872-5FE3-4574-8684-ACA1047AFA23",
            blackPlayerID = "AB98F326-AA39-4F83-8E15-EA8ECABBFC05",
            archivedByWhite = true,
        )

        // The server echoes ids uppercase; this client stores its own lowercase.
        assertTrue(game.isArchivedBy("96596872-5fe3-4574-8684-aca1047afa23"))
        // The opponent archiving it says nothing about our seat.
        assertFalse(game.isArchivedBy("ab98f326-aa39-4f83-8e15-ea8ecabbfc05"))
        assertFalse(game.isArchivedBy("someone-else"))
        assertFalse(game.isArchivedBy(null))
    }

    @Test
    fun decodesGameWithNullLanMove() {
        // Draw-offer / resign log entries carry `lan: null`; a non-null Move.lan would make the
        // whole game fail to deserialize.
        val body = """{"game":{"id":"g","fen":"f","status":"resigned","createdAt":"x","updatedAt":"y",""" +
            """"whitePlayerID":"W","blackPlayerID":"B"},"moves":[""" +
            """{"moveNumber":1,"lan":"e2e4","fenAfter":"f1","timestamp":"t","id":"m1"},""" +
            """{"moveNumber":2,"lan":null,"fenAfter":"f1","timestamp":"t","id":"m2"}]}"""

        val detail = json.decodeFromString<GameDetail>(body)

        assertEquals(2, detail.moves.size)
        assertEquals("e2e4", detail.moves[0].lan)
        assertNull(detail.moves[1].lan)
        // This body predates the action fields; they must stay optional so older games decode.
        assertNull(detail.moves[1].drawOfferPlayerID)
        assertNull(detail.moves[1].drawAcceptPlayerID)
        assertNull(detail.moves[1].drawDeclinePlayerID)
        assertNull(detail.moves[1].resignPlayerID)
    }

    @Test
    fun decodesDrawOfferAndResignMoveEntries() {
        // Real MoveDTO shape for the two entries that end (or try to end) a game: `lan` is null
        // and exactly one actor id is set, the rest omitted entirely (Swift encodes nil as absent).
        val body = """{"game":{"id":"g","fen":"f","status":"resigned","createdAt":"x","updatedAt":"y",""" +
            """"whitePlayerID":"W","blackPlayerID":"B"},"moves":[""" +
            """{"moveNumber":2,"lan":null,"fenAfter":"f1","timestamp":"t","id":"m1",""" +
            """"drawOfferPlayerID":"AB98F326-AA39-4F83-8E15-EA8ECABBFC05"},""" +
            """{"moveNumber":2,"lan":null,"fenAfter":"f1","timestamp":"u","id":"m2",""" +
            """"resignPlayerID":"96596872-5FE3-4574-8684-ACA1047AFA23"}]}"""

        val detail = json.decodeFromString<GameDetail>(body)

        assertEquals("AB98F326-AA39-4F83-8E15-EA8ECABBFC05", detail.moves[0].drawOfferPlayerID)
        assertNull(detail.moves[0].resignPlayerID)
        assertEquals("96596872-5FE3-4574-8684-ACA1047AFA23", detail.moves[1].resignPlayerID)
        assertNull(detail.moves[1].drawOfferPlayerID)
    }

    @Test
    fun decodesDrawAcceptAndDeclineMoveEntries() {
        val body = """{"game":{"id":"g","fen":"f","status":"draw","createdAt":"x","updatedAt":"y",""" +
            """"whitePlayerID":"W","blackPlayerID":"B"},"moves":[""" +
            """{"moveNumber":2,"lan":null,"fenAfter":"f1","timestamp":"t","id":"m1",""" +
            """"drawDeclinePlayerID":"B"},""" +
            """{"moveNumber":3,"lan":null,"fenAfter":"f1","timestamp":"u","id":"m2",""" +
            """"drawAcceptPlayerID":"W"}]}"""

        val detail = json.decodeFromString<GameDetail>(body)

        assertEquals("B", detail.moves[0].drawDeclinePlayerID)
        assertNull(detail.moves[0].drawAcceptPlayerID)
        assertEquals("W", detail.moves[1].drawAcceptPlayerID)
        assertNull(detail.moves[1].drawDeclinePlayerID)
    }

    @Test
    fun decodesInviteResponse() {
        val body = """{"gameID":"2A787C32","phrase":"ADKRF","expiresAt":"2026-07-30T14:13:39Z"}"""

        val response = json.decodeFromString<InviteResponse>(body)

        assertEquals("2A787C32", response.gameID)
        assertEquals("ADKRF", response.phrase)
        assertEquals("2026-07-30T14:13:39Z", response.expiresAt)
    }

    @Test
    fun decodesJoinResponseThenResolvesJoinerAsWhite() {
        // After join the server fills the white seat with the joiner and echoes it UPPERCASE.
        val body = """{"game":{"id":"2A787C32","fen":"f","status":"active","createdAt":"x","updatedAt":"y",""" +
            """"whitePlayerID":"96596872-5FE3-4574-8684-ACA1047AFA23",""" +
            """"blackPlayerID":"AB98F326-AA39-4F83-8E15-EA8ECABBFC05"}}"""

        val response = json.decodeFromString<JoinResponse>(body)
        val myLowercaseId = "96596872-5fe3-4574-8684-aca1047afa23"

        assertEquals("active", response.game.status)
        // The color predicate used by JoinByPhraseScreen / GameView must resolve white, not black.
        assertTrue(samePlayer(response.game.whitePlayerID, myLowercaseId))
    }

    private companion object {
        // A board a few moves in, so a test FEN's move counter isn't the ambiguous opening.
        const val MIDGAME_BOARD = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR"
    }

    private fun game(
        status: String,
        fen: String = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        whitePlayerID: String? = "W",
        blackPlayerID: String? = "B",
    ) = Game(
        id = "g",
        fen = fen,
        whitePlayerID = whitePlayerID,
        blackPlayerID = blackPlayerID,
        status = status,
        createdAt = "x",
        updatedAt = "y",
    )

    @Test
    fun labelsWaitingGameInWords() {
        assertEquals("waiting for opponent", game("waitingForOpponent").statusLabel("W"))
    }

    @Test
    fun labelsActiveGameByWhoseTurnItIs() {
        // Game.fen is the position the last move was played *from*, so its side-to-move
        // field names whoever just moved — the turn belongs to the other color.
        val whiteJustMoved = game("active", fen = "$MIDGAME_BOARD w KQkq - 0 2")
        assertEquals("their turn", whiteJustMoved.statusLabel("W"))
        assertEquals("your turn", whiteJustMoved.statusLabel("B"))

        val blackJustMoved = game("active", fen = "$MIDGAME_BOARD b KQkq - 0 2")
        assertEquals("your turn", blackJustMoved.statusLabel("W"))
        assertEquals("their turn", blackJustMoved.statusLabel("B"))
    }

    @Test
    fun matchesSeatsCaseInsensitivelyForTurn() {
        // The server echoes ids UPPERCASE; the locally stored id is lowercase.
        val active = game(
            "active",
            fen = "$MIDGAME_BOARD b KQkq - 0 2",
            whitePlayerID = "96596872-5FE3-4574-8684-ACA1047AFA23",
        )
        assertEquals("your turn", active.statusLabel("96596872-5fe3-4574-8684-aca1047afa23"))
    }

    @Test
    fun fallsBackToNeutralActiveLabelWhenTurnIsUnknowable() {
        val midGame = game("active", fen = "$MIDGAME_BOARD b KQkq - 0 2")
        // No local identity, and not seated in this game.
        assertEquals("in progress", midGame.statusLabel(null))
        assertEquals("in progress", midGame.statusLabel("someone-else"))
        // A FEN with no side-to-move field.
        assertEquals("in progress", game("active", fen = "f").statusLabel("W"))
        // The opening position: reads the same before white's first move and just after it.
        assertEquals("in progress", game("active").statusLabel("W"))
        assertEquals("in progress", game("active").statusLabel("B"))
    }

    @Test
    fun labelsFinishedGames() {
        assertEquals("checkmate", game("checkmate").statusLabel("W"))
        assertEquals("stalemate", game("stalemate").statusLabel("W"))
        assertEquals("draw", game("draw").statusLabel("W"))
        assertEquals("resigned", game("resigned").statusLabel("W"))
    }

    @Test
    fun humanizesUnknownStatus() {
        // A status this build doesn't know about must still read as words, not camelCase.
        assertEquals("timed out", game("timedOut").statusLabel("W"))
        assertEquals("abandoned", game("abandoned").statusLabel("W"))
    }
}
