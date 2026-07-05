package dev.neoneon.flamingo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://neoneon.dev/flamingo"

internal class FlamingoApi {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun listGames(playerId: String): Result<List<Game>> = runCatching {
        val response = client.get("$BASE_URL/games?playerID=$playerId")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        response.body()
    }

    suspend fun fetchGame(gameId: String): Result<GameDetail> = runCatching {
        val response = client.get("$BASE_URL/games/$gameId")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        response.body()
    }

    suspend fun createGame(
        gameId: String,
        lan: String,
        san: String,
        fen: String,
        moveNumber: Int,
        whitePlayerId: String,
        callerPlayerId: String,
    ): Result<GameDetail> = runCatching {
        val response = client.post("$BASE_URL/games") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateGameRequest(
                    gameID = gameId,
                    lan = lan,
                    san = san,
                    fen = fen,
                    moveNumber = moveNumber,
                    whitePlayerID = whitePlayerId,
                    callerPlayerID = callerPlayerId,
                )
            )
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        response.body()
    }

    suspend fun recordMove(
        gameId: String,
        lan: String,
        san: String,
        fen: String,
        moveNumber: Int,
        callerPlayerId: String,
        whitePlayerId: String,
    ): Result<RecordMoveResult> = runCatching {
        val response = client.post("$BASE_URL/games/$gameId/moves") {
            contentType(ContentType.Application.Json)
            setBody(
                RecordMoveRequest(
                    lan = lan,
                    san = san,
                    fen = fen,
                    moveNumber = moveNumber,
                    callerPlayerID = callerPlayerId,
                    whitePlayerID = whitePlayerId,
                )
            )
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        response.body()
    }

    fun close() {
        client.close()
    }
}

@Serializable
private data class CreateGameRequest(
    val gameID: String,
    val lan: String,
    val san: String,
    val fen: String,
    val moveNumber: Int,
    val whitePlayerID: String,
    val callerPlayerID: String,
)

@Serializable
private data class RecordMoveRequest(
    val lan: String,
    val san: String,
    val fen: String,
    val moveNumber: Int,
    val callerPlayerID: String,
    val whitePlayerID: String,
)
