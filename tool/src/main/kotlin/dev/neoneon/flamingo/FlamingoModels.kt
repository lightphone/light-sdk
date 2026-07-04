package dev.neoneon.flamingo

import kotlinx.serialization.Serializable

@Serializable
data class Game(
    val id: String,
    val fen: String,
    val whitePlayerID: String,
    val blackPlayerID: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

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
