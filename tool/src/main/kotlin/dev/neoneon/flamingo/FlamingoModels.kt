package dev.neoneon.flamingo

import kotlinx.serialization.Serializable

@Serializable
data class Game(
    val id: String,
    val fen: String,
    // Nullable: a phrase invite created by a black initiator leaves the white seat open
    // (server returns null) until a joiner fills it.
    val whitePlayerID: String? = null,
    val blackPlayerID: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    // Per seat: each player hides a finished game from their own list without touching the
    // opponent's. Both flags come back to both players. Optional, so a body without them decodes.
    val archivedByWhite: Boolean = false,
    val archivedByBlack: Boolean = false,
)

/**
 * Whether [playerId]'s seat has put this game in its archive. False for anyone in neither seat.
 * Compared through [samePlayer], as the server echoes ids uppercase.
 */
fun Game.isArchivedBy(playerId: String?): Boolean =
    (archivedByWhite && samePlayer(playerId, whitePlayerID)) ||
        (archivedByBlack && samePlayer(playerId, blackPlayerID))

/**
 * The raw backend status rendered for humans — `waitingForOpponent` is no way to talk to
 * someone. `active` only means "in progress", so it's resolved against [playerId] into whose
 * move it actually is. The server declares checkmate/stalemate but never writes them today;
 * they're handled anyway, and anything unrecognized falls back to the split camelCase word.
 */
fun Game.statusLabel(playerId: String?): String = when (status) {
    "waitingForOpponent" -> "waiting for opponent"
    "active" -> when (isMyTurn(playerId)) {
        true -> "your turn"
        false -> "their turn"
        null -> "in progress"
    }
    // Neither the winner of a checkmate nor the resigning player is knowable from a list
    // entry alone (the resigner lives on the move log), so these stay neutral.
    "checkmate" -> "checkmate"
    "stalemate" -> "stalemate"
    "draw" -> "draw"
    "resigned" -> "resigned"
    else -> status.humanized()
}

/**
 * Whether it's this player's move, or null when that can't be told.
 *
 * `Game.fen` is the position the last move was played *from*, not the one it produced:
 * both clients send the pre-move FEN (see GameView.submitMove) into the server's
 * `fen_after` field, and the recorder copies it onto the game — the iOS companion reads
 * it back the same way, deriving the mover from this field. So the side to move named in
 * the FEN is whoever just moved, and the move belongs to the *other* color.
 *
 * Reads the FEN's fields directly rather than building a Board per list row. Null when
 * there's no local identity, we hold neither seat, or the FEN carries no side-to-move.
 */
private fun Game.isMyTurn(playerId: String?): Boolean? {
    val iAmWhite = when {
        samePlayer(playerId, whitePlayerID) -> true
        samePlayer(playerId, blackPlayerID) -> false
        else -> return null
    }
    val fields = fen.split(' ')
    val whiteJustMoved = when (fields.getOrNull(1)) {
        "w" -> true
        "b" -> false
        else -> return null
    }
    // The opening position is the one case the pre-move FEN can't resolve: it looks
    // identical before white's first move and right after it (that move was played
    // *from* the opening), and the list payload carries no move count to break the tie.
    if (whiteJustMoved && fields.getOrNull(5) == "1") return null
    return iAmWhite != whiteJustMoved
}

private val camelCaseBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])")

/** `timedOut` -> `timed out`, so an unrecognized status still reads as words. */
private fun String.humanized(): String =
    split(camelCaseBoundary).joinToString(" ") { it.lowercase() }

@Serializable
data class Move(
    val moveNumber: Int,
    // Nullable: draw-offer / draw-accept / draw-decline / resign log entries carry no
    // chess move, so the server sends `lan: null` for them (MoveDTO.lan is optional).
    // A non-null type here fails to deserialize any game that contains such an action.
    val lan: String? = null,
    val fenAfter: String,
    val timestamp: String,
    val id: String,
    // The four game actions the backend records as move-log entries rather than on the game
    // itself. At most one is set on any entry, and an entry that sets one carries no [lan] —
    // the actor's id is the whole payload, and the only record of *who* acted (the game's status
    // just says `resigned` or `draw`). See List<Move>.resignedColor and gameActionState. Optional
    // so bodies written before this build — and the plain moves that make up most of a log — decode.
    val drawOfferPlayerID: String? = null,
    val drawAcceptPlayerID: String? = null,
    val drawDeclinePlayerID: String? = null,
    val resignPlayerID: String? = null,
)

@Serializable
data class GameDetail(
    val game: Game,
    val moves: List<Move>,
)

@Serializable
data class RecordMoveResult(
    val move: Move,
    val game: Game,
)

/** Response to `POST /flamingo/games/invite` — the freshly created waiting game plus its share phrase. */
@Serializable
data class InviteResponse(
    val gameID: String,
    val phrase: String,
    val expiresAt: String,
)

/** Response to `POST /flamingo/games/:id/archive` — the game with our seat's flag set. */
@Serializable
data class ArchiveResponse(
    val game: Game,
)

/** Response to `POST /flamingo/games/join-by-phrase` — the game after our seat was filled. */
@Serializable
data class JoinResponse(
    val game: Game,
)

/** Response to the `/flamingo/players` routes — one player's display name. */
@Serializable
data class PlayerName(
    val playerID: String,
    val name: String,
)

/**
 * Display names keyed for lookup by player id.
 *
 * Lower-cased keys, because the ids being looked up come from two places that disagree on case:
 * this client mints lowercase [java.util.UUID] strings while the server echoes them back
 * uppercase (the same mismatch [samePlayer] exists for). A map keyed by the raw string would miss
 * every one of its own entries.
 */
fun List<PlayerName>.byPlayerId(): Map<String, String> =
    associate { it.playerID.lowercase() to it.name }

/**
 * How a game is titled in the list: `"White vs orange489"` — the color we hold, then whoever we
 * are playing.
 *
 * Our own name is the one name on the row that tells the reader nothing, and the color we play is
 * the one fact the list otherwise never states; in a list of our own games that trade is worth
 * making. It does mean the two players see different titles for the same game.
 *
 * Falls back to `"blueberry764 vs orange489"`, white first, when [playerId] holds neither seat —
 * there is no "our color" to name then, and white-first is how a chess game is written.
 *
 * Each seat degrades on its own. An unfilled seat — the open one on an invite nobody has claimed
 * yet — reads "open seat"; a seat whose name hasn't arrived falls back to the short form of its
 * id, so a failed lookup still produces a title of the usual shape rather than a blank row.
 */
fun Game.title(playerId: String?, names: Map<String, String>): String = when {
    samePlayer(playerId, whitePlayerID) -> "White vs ${seatLabel(blackPlayerID, names)}"
    samePlayer(playerId, blackPlayerID) -> "Black vs ${seatLabel(whitePlayerID, names)}"
    else -> "${seatLabel(whitePlayerID, names)} vs ${seatLabel(blackPlayerID, names)}"
}

private fun seatLabel(playerId: String?, names: Map<String, String>): String {
    if (playerId == null) return "open seat"
    return names[playerId.lowercase()] ?: playerId.take(8)
}
