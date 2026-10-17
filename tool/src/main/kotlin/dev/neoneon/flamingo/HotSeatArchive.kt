package dev.neoneon.flamingo

import dev.neoneon.chesskit.Piece
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

/**
 * How a finished local game ended, in the three words iOS stores it as — so the archive's shape
 * doesn't depend on how chesskit happens to name its colors, and the two clients stay comparable.
 */
@Serializable
enum class ArchivedOutcome(val label: String) {
    @SerialName("whiteWins")
    WhiteWins("White won"),

    @SerialName("blackWins")
    BlackWins("Black won"),

    @SerialName("draw")
    Draw("Draw"),
}

/**
 * A finished hot seat game, saved to the archive from its game actions. Like the game itself it
 * lives only on this device. Mirrors iOS's `ArchivedHotSeatGame`.
 *
 * The outcome is kept next to the moves, although the moves alone decide it: the archive lists
 * every game's result, and finding it out would cost the list a full replay per row.
 */
@Serializable
data class ArchivedHotSeatGame(
    val game: HotSeatGame,
    val outcome: ArchivedOutcome,
    val archivedAtMillis: Long,
) {
    val id: String get() = game.id
}

/** A hot seat game only ends on the board, but a resignation is mapped too so this stays total. */
internal fun GameOutcome.archived(): ArchivedOutcome = when (this) {
    is GameOutcome.Checkmate ->
        if (winner == Piece.Color.white) ArchivedOutcome.WhiteWins else ArchivedOutcome.BlackWins
    is GameOutcome.Resigned ->
        if (color == Piece.Color.white) ArchivedOutcome.BlackWins else ArchivedOutcome.WhiteWins
    GameOutcome.Stalemate, is GameOutcome.Drawn -> ArchivedOutcome.Draw
}

/** When the game was saved, told the way the games list tells a game's last activity. */
internal fun ArchivedHotSeatGame.archivedAgo(now: Instant = Instant.now()): String =
    relativeTime(Instant.ofEpochMilli(archivedAtMillis), now)

/** [game] at the top of the archive, replacing an earlier copy with the same id. */
internal fun List<ArchivedHotSeatGame>.adding(game: ArchivedHotSeatGame): List<ArchivedHotSeatGame> =
    listOf(game) + filterNot { it.id == game.id }

internal fun List<ArchivedHotSeatGame>.removing(id: String): List<ArchivedHotSeatGame> =
    filterNot { it.id == id }

private val archiveSerializer = ListSerializer(ArchivedHotSeatGame.serializer())

internal fun encodeHotSeatArchive(games: List<ArchivedHotSeatGame>): String =
    hotSeatJson.encodeToString(archiveSerializer, games)

/**
 * Reads the stored archive back, or `null` for anything unreadable — most likely written by a
 * build with a different shape. Like an unreadable hot seat game there is nothing to salvage.
 */
internal fun decodeHotSeatArchive(raw: String?): List<ArchivedHotSeatGame>? {
    if (raw == null) return null
    return try {
        hotSeatJson.decodeFromString(archiveSerializer, raw)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
