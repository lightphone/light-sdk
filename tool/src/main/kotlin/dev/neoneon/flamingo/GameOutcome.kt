package dev.neoneon.flamingo

import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Piece

/** How a game ended, once it has. An unfinished game has no outcome — see [gameOutcome]. */
sealed interface GameOutcome {
    data class Checkmate(val winner: Piece.Color) : GameOutcome
    data object Stalemate : GameOutcome
    data class Resigned(val color: Piece.Color) : GameOutcome

    /** A draw that isn't stalemate — agreed, or forced by the fifty-move / material / repetition rules. */
    data class Drawn(val reason: Board.State.DrawReason) : GameOutcome
}

/**
 * The game's ending, or `null` while it's still being played.
 *
 * [resignedColor] and [agreedDraw] take precedence over [boardState], for the same reason: neither
 * is a move. The server records both as log entries carrying no LAN (so `replayMove` skips them),
 * leaving the board looking perfectly active at the moment the players stopped. Resignation wins
 * over agreement because a game can only be given up once, and whichever came second didn't happen.
 *
 * Everything else is read off chesskit's own verdict. That means [boardState] must come from a
 * board advanced *by moves* — the same constraint [checkedKingSquare] documents, and the reason
 * this takes a `Board.State` rather than a position.
 */
internal fun gameOutcome(
    boardState: Board.State,
    resignedColor: Piece.Color?,
    agreedDraw: Boolean = false,
): GameOutcome? {
    if (resignedColor != null) return GameOutcome.Resigned(resignedColor)
    if (agreedDraw) return GameOutcome.Drawn(Board.State.DrawReason.agreement)

    return when (boardState) {
        // chesskit names the *mated* color, so the winner is the other one.
        is Board.State.checkmate -> GameOutcome.Checkmate(boardState.color.opposite)
        is Board.State.draw -> when (boardState.reason) {
            Board.State.DrawReason.stalemate -> GameOutcome.Stalemate
            else -> GameOutcome.Drawn(boardState.reason)
        }
        else -> null
    }
}

/**
 * The ending as one short line for the nav bar's second row.
 *
 * Kept to roughly the length of the "Waiting for opponent…" line it replaces: the top bar gives
 * its center 18 grid units and one line before ellipsizing.
 */
internal val GameOutcome.label: String
    get() = when (this) {
        // Piece.Color renders capitalized, which only suits the start of a line.
        is GameOutcome.Checkmate -> "Checkmate, ${winner.toString().lowercase()} won"
        GameOutcome.Stalemate -> "Stalemate, a draw"
        is GameOutcome.Resigned -> "$color resigned"
        is GameOutcome.Drawn -> when (reason) {
            Board.State.DrawReason.agreement -> "Draw agreed"
            Board.State.DrawReason.fiftyMoves -> "Draw, fifty moves"
            Board.State.DrawReason.insufficientMaterial -> "Draw, too few pieces"
            Board.State.DrawReason.repetition -> "Draw, repetition"
            // Unreachable: gameOutcome maps stalemate to GameOutcome.Stalemate.
            Board.State.DrawReason.stalemate -> "Stalemate, a draw"
        }
    }

/**
 * Whether the server holds a game that ended this way as finished, which is what lets a player
 * archive it. It detects checkmate and stalemate and records resignations and agreed draws, but
 * not the draws chesskit finds on its own — repetition, fifty moves, too few pieces — so those
 * games stay `active` there, and archiving one would be refused.
 */
internal val GameOutcome.isRecordedByServer: Boolean
    get() = when (this) {
        is GameOutcome.Checkmate, GameOutcome.Stalemate, is GameOutcome.Resigned -> true
        is GameOutcome.Drawn -> reason == Board.State.DrawReason.agreement
    }

/**
 * Which seat [playerId] holds, or `null` if it holds neither.
 *
 * Both records of a resignation name a player rather than a color — the move log's
 * `resignPlayerID` and the live frame's `player` — so both have to come back through here.
 * [samePlayer] treats nulls as never matching, so an unfilled seat can't be mistaken for anyone.
 */
internal fun colorOfPlayer(
    playerId: String?,
    whitePlayerId: String?,
    blackPlayerId: String?,
): Piece.Color? = when {
    samePlayer(playerId, whitePlayerId) -> Piece.Color.white
    samePlayer(playerId, blackPlayerId) -> Piece.Color.black
    else -> null
}

/**
 * The color that resigned [this] game, or `null` if nobody did.
 *
 * The resignation lives on the move log rather than the game record — the server writes
 * `resignPlayerID` onto the log entry and only flips the game's status to `resigned`.
 */
internal fun List<Move>.resignedColor(
    whitePlayerId: String?,
    blackPlayerId: String?,
): Piece.Color? = colorOfPlayer(
    playerId = firstOrNull { it.resignPlayerID != null }?.resignPlayerID,
    whitePlayerId = whitePlayerId,
    blackPlayerId = blackPlayerId,
)
