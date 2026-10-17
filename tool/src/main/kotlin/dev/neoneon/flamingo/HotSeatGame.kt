package dev.neoneon.flamingo

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The one hot seat game: two people passing a single phone. It lives only on this device and
 * never reaches the server. Players see it as "Local"; the code keeps the iOS name so the two
 * clients stay comparable.
 *
 * Kept as the moves played rather than as a FEN. Replaying them through one chesskit `Board`
 * rebuilds the position *and* the history it came from, so threefold repetition and the
 * fifty-move rule still hold after a relaunch or a take-back — a FEN alone would forget which
 * positions have already been seen.
 *
 * [id] and [startedAtMillis] mirror iOS's `HotSeatGame`, which its archive keys finished games by.
 */
@Serializable
data class HotSeatGame(
    val id: String = UUID.randomUUID().toString(),
    val startedAtMillis: Long = System.currentTimeMillis(),
    /** Every move so far in LAN (`e2e4`, `e7e8q`), white's first. */
    val moves: List<String> = emptyList(),
)

// Shared with the archive (HotSeatArchive.kt), which keeps finished games in this same shape.
internal val hotSeatJson = Json { ignoreUnknownKeys = true }

internal fun encodeHotSeatGame(game: HotSeatGame): String = hotSeatJson.encodeToString(HotSeatGame.serializer(), game)

/**
 * Reads a stored game back, or `null` for anything unreadable — most likely written by a build
 * with a different shape. There is nothing to salvage from it, so the caller drops it.
 */
internal fun decodeHotSeatGame(raw: String?): HotSeatGame? {
    if (raw == null) return null
    return try {
        hotSeatJson.decodeFromString(HotSeatGame.serializer(), raw)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
