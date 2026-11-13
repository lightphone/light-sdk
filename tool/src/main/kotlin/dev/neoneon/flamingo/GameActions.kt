package dev.neoneon.flamingo

/**
 * A draw offer's standing, from the local player's point of view.
 *
 * The backend has no draw-offer flag: an offer is a move-log entry carrying only the offerer's
 * id (see [Move]), so which of these applies depends on *who* made the last entry — hence every
 * comparison here goes through [samePlayer].
 */
sealed interface DrawOffer {
    /** Nothing outstanding. */
    data object None : DrawOffer

    /** We offered; waiting on their reply. The board stays playable — they may just move. */
    data object Outgoing : DrawOffer

    /** They offered; we owe an answer. Freezes the board until we accept or decline. */
    data object Incoming : DrawOffer

    /** They turned our offer down. Informational only; cleared by our next move. */
    data object Declined : DrawOffer
}

/**
 * What a game's move log says about draws.
 *
 * How a game *ended* is [GameOutcome]'s job, which reads chesskit's verdict on the board — but an
 * agreed draw is invisible there (no move produces it), so it has to be carried out of the log
 * alongside the offer's standing and handed to [gameOutcome].
 */
internal data class GameActionState(
    val drawOffer: DrawOffer = DrawOffer.None,
    val agreedDraw: Boolean = false,
)

/**
 * Reads a game's draw standing out of its move log, as [localPlayerId] sees it.
 *
 * Draw offers and their replies are recorded as move-log entries with no LAN and one actor id, so
 * this is the only way to recover them after a restart or a re-sync — the live socket alone only
 * reports what arrives while we're connected. [moves] must already be ordered (see
 * `GameViewViewModel.loadExistingGame`).
 *
 * Our own actions come back in the log too, which is why the actor of each entry is compared with
 * [samePlayer] — the server echoes ids uppercase — to tell an offer we're waiting on from one we
 * owe an answer to.
 *
 * An offer only stands while it's the *last* entry: under FIDE rules an offer lapses once the
 * opponent replies or plays, and both of those append an entry after it.
 */
internal fun gameActionState(moves: List<Move>, localPlayerId: String?): GameActionState {
    // An agreed draw ends the game whoever accepted, so the acceptor's identity doesn't matter —
    // and it settles the offer that produced it, leaving nothing outstanding to answer.
    if (moves.any { it.drawAcceptPlayerID != null }) return GameActionState(agreedDraw = true)

    val last = moves.lastOrNull() ?: return GameActionState()
    last.drawOfferPlayerID?.let { offerer ->
        return GameActionState(
            drawOffer = if (samePlayer(offerer, localPlayerId)) DrawOffer.Outgoing else DrawOffer.Incoming,
        )
    }
    last.drawDeclinePlayerID?.let { decliner ->
        // Only worth surfacing when they turned *us* down — our own decline needs no report.
        if (!samePlayer(decliner, localPlayerId)) return GameActionState(drawOffer = DrawOffer.Declined)
    }
    return GameActionState()
}

/**
 * The half-move number [fen] belongs to: the ply about to be played from that position.
 *
 * `moveNumber` is a half-move index, derived from the position rather than counted from the log —
 * the same rule the iOS companion and the backend follow, so both clients name a ply identically.
 * Draw and resign entries carry the *current* position, which makes this the number of the move
 * they precede: they deliberately share that move's slot, and the backend keeps one row per played
 * ply while letting actions sit alongside it.
 *
 * This client used to number every entry `max + 1` instead, treating the column as an opaque log
 * sequence. That was self-consistent, but it drifted a slot ahead of the FEN-derived numbering as
 * soon as both sides acted on one draw offer — and in a mixed game against iOS the drift landed
 * our move on the number iOS had reserved for the *next* ply, so the backend rejected that ply as
 * a divergence and dropped it from the history.
 *
 * FEN fields: `[position] [side] [castling] [ep] [halfclock] [fullmove]`. White's first move is 1,
 * black's is 2, white's second is 3. Falls back to 1 on a FEN this can't read, matching the
 * companion.
 */
internal fun halfMoveNumber(fen: String): Int {
    val fields = fen.split(' ')
    val fullmove = fields.getOrNull(5)?.toIntOrNull() ?: return 1
    val blackToMove = fields.getOrNull(1) == "b"
    return (fullmove - 1) * 2 + if (blackToMove) 2 else 1
}

/**
 * The body of the confirmation before deleting a finished game, for the list it is in.
 *
 * The two say different things on purpose. In the archive, Delete sits beside Unarchive, and the
 * one thing worth saying first is that deleting isn't that: the game won't go back to the games
 * list. From the games list, it's the archive that deleting skips. Both end the same way — there
 * is no undo, and only this seat's lists change; the opponent's don't, whatever they did with it.
 *
 * Hand-broken into short lines, like the other confirmations: at the copy style's size, about
 * thirty characters fill the Light Phone's width.
 */
internal fun deleteConfirmation(isArchived: Boolean): String =
    if (isArchived) {
        "Deleting isn't unarchiving.\nThe game leaves your archive\nand can't be brought back.\nOnly your lists change."
    } else {
        "The game leaves your games\nand won't go to your archive.\nIt can't be brought back.\nOnly your lists change."
    }
