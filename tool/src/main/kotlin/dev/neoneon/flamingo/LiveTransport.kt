package dev.neoneon.flamingo

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Minimal live-wire action exchanged over the WebSocket transport.
 *
 * The game is identified by the socket connection (the gameId is in the path), so
 * it is never repeated in the frame. Only the intent, the acting player and the
 * move delta travel on the wire:
 *
 * `{ "intent": "move", "player": "<uuid>", "lan": "e2e4", "fen": "<pre-move FEN>", "n": 3 }`
 *
 * `lan`/`fen`/`n` are present for moves; draw/resign frames omit them.
 */
@Serializable
data class LiveAction(
    val intent: String,
    val player: String,
    val lan: String? = null,
    val fen: String? = null,
    val n: Int? = null,
) {
    companion object {
        const val INTENT_MOVE = "move"
        const val INTENT_OFFER_DRAW = "offerDraw"
        const val INTENT_ACCEPT_DRAW = "acceptDraw"
        const val INTENT_DECLINE_DRAW = "declineDraw"
        const val INTENT_RESIGN = "resign"

        val KNOWN_INTENTS = setOf(
            INTENT_MOVE, INTENT_OFFER_DRAW, INTENT_ACCEPT_DRAW, INTENT_DECLINE_DRAW, INTENT_RESIGN,
        )
    }
}

/** An event surfaced by a [LiveTransport] to its consumer. */
sealed interface LiveEvent {
    /** The peer performed an action (move / draw / resign) — apply it live. */
    data class Action(val action: LiveAction) : LiveEvent

    /**
     * The server rejected our last action, or the connection dropped unexpectedly.
     * The consumer should re-sync over HTTP.
     */
    data object NeedsResync : LiveEvent
}

/**
 * Port: the live-move transport the game screen needs from the outside world.
 *
 * Concrete adapters (e.g. [KtorLiveTransport]) implement this over a real
 * WebSocket. The screen/view-model depends only on this interface.
 */
interface LiveTransport {
    /** Inbound events. Collect once for the lifetime of the screen. */
    val events: Flow<LiveEvent>

    /** Opens a live connection for [gameId], identifying the local [playerId]. */
    suspend fun connect(gameId: String, playerId: String)

    /** Closes the connection (e.g. when backgrounded). Safe to call when disconnected. */
    suspend fun disconnect()

    /** Sends a local action to the peer(s) via the server, which persists it. */
    suspend fun send(action: LiveAction)

    /** Releases the underlying client. Call from `onCleared`. */
    fun close()
}
