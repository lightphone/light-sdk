package dev.neoneon.flamingo

import kotlinx.serialization.Serializable

@Serializable
data class Game(
    val id: String,
    val fen: String,
    val whitePlayerID: String,
    val blackPlayerID: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

/** Statuses the backend uses for a game that's still in progress; anything else is over. */
private val activeGameStatuses = setOf("active", "waitingForOpponent")

val Game.isActive: Boolean get() = status in activeGameStatuses

@Serializable
data class Move(
    val moveNumber: Int,
    val lan: String,
    val fenAfter: String,
    val timestamp: String,
    val id: String,
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
