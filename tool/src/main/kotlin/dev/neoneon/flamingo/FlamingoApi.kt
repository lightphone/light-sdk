package dev.neoneon.flamingo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private const val BASE_URL = "https://neoneon.dev/flamingo"

/** The path prefix BASE_URL resolves to, and the one that gets signed. */
private const val BASE_PATH = "/flamingo"

internal class FlamingoApi(private val keyStore: PlayerKeyStore = PlayerKeyStore()) {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /**
     * [playerId]'s games: the live list, or with [archived] the ones this seat has archived. The
     * two never overlap, and archiving is per seat, so the opponent's lists are unaffected.
     */
    suspend fun listGames(playerId: String, archived: Boolean = false): Result<List<Game>> = runCatching {
        val response = client.get("$BASE_URL/games?playerID=$playerId&archived=$archived")
        if (!response.status.isSuccess()) {
            // Keeps the status, so the games list can tell a deliberate rejection (and go read
            // the status document) from an ordinary failure.
            throw HttpStatusException(
                response.status.value,
                "HTTP ${response.status.value}: ${response.bodyAsText().take(500)}",
            )
        }
        response.body()
    }

    /**
     * Moves a finished game into [playerId]'s archive, or with [archived] `false` back out of it.
     * Only this seat's flag changes, and the game's `updatedAt` stays as it was. The server
     * refuses a game it doesn't hold as finished (409) — see [GameOutcome.isRecordedByServer].
     */
    suspend fun setArchived(gameId: String, playerId: String, archived: Boolean): Result<Game> = runCatching {
        val response = client.post("$BASE_URL/games/$gameId/archive") {
            contentType(ContentType.Application.Json)
            setBody(ArchiveRequest(playerID = playerId, archived = archived))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(archiveErrorMessage(response.status.value, archived))
        }
        response.body<ArchiveResponse>().game
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

    /**
     * Creates an invite game (one seat open, waiting for an opponent) and mints its share
     * phrase. Exactly one of [whitePlayerId] / [blackPlayerId] is the creator's chosen seat;
     * the other is left null and filled when someone joins by phrase. No first move is sent —
     * the game is created empty and the creator plays their opening move later in [GameView].
     */
    suspend fun createInvite(
        whitePlayerId: String?,
        blackPlayerId: String?,
    ): Result<InviteResponse> = runCatching {
        val response = client.post("$BASE_URL/games/invite") {
            contentType(ContentType.Application.Json)
            setBody(
                InviteRequest(
                    whitePlayerID = whitePlayerId,
                    blackPlayerID = blackPlayerId,
                    origin = LIGHT_PHONE_ORIGIN,
                )
            )
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(inviteErrorMessage(response.status.value))
        }
        response.body()
    }

    /**
     * Re-fetches [gameId]'s share phrase from the server (minting a fresh one there if the
     * previous one expired), so the game view can re-open the share screen without the client
     * ever persisting the phrase. Only valid while the game still has an open seat.
     */
    suspend fun shareInvite(gameId: String): Result<InviteResponse> = runCatching {
        val response = client.post("$BASE_URL/games/$gameId/invite")
        if (!response.status.isSuccess()) {
            throw IllegalStateException(shareErrorMessage(response.status.value))
        }
        response.body()
    }

    /** Joins the invite addressed by [phrase], filling its open seat and activating the game. */
    suspend fun joinByPhrase(
        phrase: String,
        playerId: String,
    ): Result<JoinResponse> = runCatching {
        val response = client.post("$BASE_URL/games/join-by-phrase") {
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(phrase = phrase, playerID = playerId))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(joinErrorMessage(response.status.value))
        }
        response.body()
    }

    /**
     * Claims this installation's player id and returns the display name the server holds for it.
     *
     * Called once per install (see [PlayerIdentityStore.ensureRegistered]). The server mints a
     * colour name for an id it has never seen; for one it already knows — because the opponent's
     * move created the row first, or because a previous launch already registered — it returns the
     * existing name untouched. Registering twice therefore costs the player nothing.
     */
    suspend fun registerPlayer(playerId: String): Result<PlayerName> = runCatching {
        val publicKey = keyStore.publicKeyBase64()
            ?: throw IllegalStateException("This device couldn't create a signing key")

        // Registration self-authenticates: the signature is checked against the key in this very
        // body, proving we hold the private half. It cannot use the normal gate, because the key
        // that gate would check is the one being presented.
        val json = Json.encodeToString(
            RegisterPlayerRequest.serializer(),
            RegisterPlayerRequest(playerID = playerId, publicKey = publicKey),
        )
        val response = client.post("$BASE_URL/players") {
            setBody(TextContent(json, ContentType.Application.Json))
            signed("POST", "$BASE_PATH/players", playerId, json)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(registerErrorMessage(response.status.value))
        }
        response.body()
    }

    /**
     * The display names for [playerIds], in one request — what the games list calls so it can
     * title every row without a request per seat.
     *
     * Ids the server has no player for are simply absent from the result; the caller falls back
     * per seat (see [Game.title]) rather than losing the whole batch. An empty input skips the
     * request entirely.
     */
    suspend fun fetchPlayerNames(playerIds: Collection<String>): Result<List<PlayerName>> = runCatching {
        if (playerIds.isEmpty()) return@runCatching emptyList()
        val ids = playerIds.distinct().joinToString(",")
        val response = client.get("$BASE_URL/players?ids=$ids")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        response.body()
    }

    /**
     * Renames [playerId] to [name] — the write behind the Account screen.
     *
     * Signed: the server has no unsigned path for editing personal information.
     */
    suspend fun renamePlayer(playerId: String, name: String): Result<PlayerName> = runCatching {
        val json = Json.encodeToString(RenamePlayerRequest.serializer(), RenamePlayerRequest(name = name))
        val response = client.patch("$BASE_URL/players/$playerId") {
            setBody(TextContent(json, ContentType.Application.Json))
            signed("PATCH", "$BASE_PATH/players/$playerId", playerId, json)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(renameErrorMessage(response.status.value))
        }
        response.body()
    }

    /**
     * Applies the four signing headers.
     *
     * Takes the method, path and body it is going to sign rather than reading them back off the
     * builder, because Ktor's [HttpRequestBuilder] does not expose the serialized body — so the
     * caller passes the same [body] string it sent. Signing bytes that are not the bytes sent is
     * the one mistake this scheme cannot survive, which is also why the signed calls serialize by
     * hand into a [TextContent] rather than handing an object to content negotiation: a String
     * passed to `setBody` under a JSON content type would be re-encoded as a quoted JSON string,
     * and the body hash would describe something the server never received.
     */
    private fun HttpRequestBuilder.signed(
        method: String,
        path: String,
        playerId: String,
        body: String,
    ) {
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = UUID.randomUUID().toString()
        val payload = SignedRequestPayload.canonical(
            method = method,
            path = path,
            playerId = playerId,
            timestamp = timestamp,
            nonce = nonce,
            body = body,
        )
        val signature = keyStore.sign(payload)
            ?: throw IllegalStateException("This device couldn't sign the request")

        header(SignedRequestPayload.HEADER_PLAYER, playerId)
        header(SignedRequestPayload.HEADER_TIMESTAMP, timestamp.toString())
        header(SignedRequestPayload.HEADER_NONCE, nonce)
        header(SignedRequestPayload.HEADER_SIGNATURE, signature)
    }

    fun close() {
        client.close()
    }
}

private const val LIGHT_PHONE_ORIGIN = "lightPhone"

/** A non-2xx answer, with its status kept for callers that treat some statuses differently. */
internal class HttpStatusException(val status: Int, message: String) : IllegalStateException(message)

/** Short, top-bar-friendly messages for the invite/join error statuses (see docs/flamingo-api.md). */
private fun inviteErrorMessage(status: Int): String = when (status) {
    503 -> "Too many games — try again"
    else -> "Couldn't create game ($status)"
}

private fun shareErrorMessage(status: Int): String = when (status) {
    404 -> "Game not found"
    409 -> "Both players joined"
    503 -> "Try again in a moment"
    else -> "Couldn't get code ($status)"
}

/**
 * 422 is the only one a player can act on: the server rejects a name that is empty, longer than
 * 24 characters, or not a single line. A 404 means this install's id was never registered, which
 * the next launch repairs on its own.
 */
/**
 * 409 is the one a player cannot fix: the id is bound to another device's key, so this
 * installation can never edit that profile. The rest resolve themselves on a later launch.
 */
private fun registerErrorMessage(status: Int): String = when (status) {
    401 -> "Couldn't verify this device"
    409 -> "This profile belongs to another device"
    422 -> "This device's key was rejected"
    else -> "Couldn't register this device ($status)"
}

private fun renameErrorMessage(status: Int): String = when (status) {
    401 -> "This device can't edit that name"
    403 -> "Signed as a different player"
    404 -> "Not registered yet - try later"
    409 -> "Already sent - try again"
    422 -> "Use 1-24 characters, one line"
    else -> "Couldn't save name ($status)"
}

// Short enough for the nav bar's second line, where the game screen shows them.
private fun archiveErrorMessage(status: Int, archived: Boolean): String = when (status) {
    403 -> "Not your game"
    404 -> "Game not found"
    409 -> "Game isn't over yet"
    else -> if (archived) "Couldn't archive ($status)" else "Couldn't unarchive ($status)"
}

private fun joinErrorMessage(status: Int): String = when (status) {
    400 -> "Enter a valid 5-letter code"
    404 -> "No game for that code"
    409 -> "Already joined"
    410 -> "Invite expired"
    else -> "Join failed ($status)"
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

// Only the seat the creator chose is non-null; the null seat is left out of the JSON
// (the Json config's encodeDefaults is off, so a field left at its null default is omitted),
// which the backend requires — exactly one seat may be set.
@Serializable
private data class InviteRequest(
    val whitePlayerID: String? = null,
    val blackPlayerID: String? = null,
    val origin: String,
)

// Neither field has a default, so both are always encoded — `archived: false` included, which
// the server needs to tell an unarchive from a malformed body.
@Serializable
private data class ArchiveRequest(
    val playerID: String,
    val archived: Boolean,
)

@Serializable
private data class RegisterPlayerRequest(
    val playerID: String,
    val publicKey: String,
)

@Serializable
private data class RenamePlayerRequest(
    val name: String,
)

@Serializable
private data class JoinRequest(
    val phrase: String,
    val playerID: String,
)
