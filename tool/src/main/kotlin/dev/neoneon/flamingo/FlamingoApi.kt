package dev.neoneon.flamingo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
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

    fun close() {
        client.close()
    }
}
