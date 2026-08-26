package com.thelightphone.chess

import com.thelightphone.chess.engine.Board
import com.thelightphone.chess.engine.BotLevel
import com.thelightphone.chess.engine.GameResult
import com.thelightphone.chess.engine.GameTimer
import com.thelightphone.chess.engine.StartColor
import kotlinx.serialization.Serializable

sealed class GameLaunch {
    data class New(
        val timer: GameTimer,
        val color: StartColor,
        val bot: BotLevel,
    ) : GameLaunch()

    data object Continue : GameLaunch()
}

@Serializable
data class SavedGame(
    val fen: String,
    val startFen: String,
    val movesUci: List<String>,
    val playerIsWhite: Boolean,
    val timerMs: Long?,
    val whiteTimeMs: Long,
    val blackTimeMs: Long,
    val clockRunningForWhite: Boolean?,
    val savedAtElapsedMs: Long,
    val botLevel: String,
    val result: String? = null,
) {
    fun bot(): BotLevel = BotLevel.valueOf(botLevel)

    fun outcome(): GameResult? = result?.let { GameResult.valueOf(it) }

    val isInProgress: Boolean get() = result == null

    fun summary(): String {
        val color = if (playerIsWhite) "White" else "Black"
        val timer = GameTimer.entries.firstOrNull { it.durationMs == timerMs }?.label ?: "No timer"
        return "$color · $timer · ${bot().label}"
    }

    companion object {
        fun replay(saved: SavedGame): Board {
            val board = Board()
            board.loadFen(saved.startFen)
            for (uci in saved.movesUci) board.applyUci(uci)
            return board
        }
    }
}

sealed class GameOverlay {
    data object None : GameOverlay()
    data class Promotion(val from: Int, val to: Int) : GameOverlay()
    data object ResignConfirm : GameOverlay()
    data class GameOver(val message: String) : GameOverlay()
}

data class GameUiState(
    val occupancy: IntArray = IntArray(64),
    val playerIsWhite: Boolean = true,
    val selected: Int? = null,
    val targets: Set<Int> = emptySet(),
    val lastFrom: Int? = null,
    val lastTo: Int? = null,
    val hintFrom: Int? = null,
    val hintTo: Int? = null,
    val whiteTimeMs: Long = 0,
    val blackTimeMs: Long = 0,
    val hasTimer: Boolean = false,
    val thinking: Boolean = false,
    val overlay: GameOverlay = GameOverlay.None,
    val inProgress: Boolean = true,
    val botLabel: String = "",
    val canUndo: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameUiState) return false
        return occupancy.contentEquals(other.occupancy) &&
            playerIsWhite == other.playerIsWhite &&
            selected == other.selected &&
            targets == other.targets &&
            lastFrom == other.lastFrom &&
            lastTo == other.lastTo &&
            hintFrom == other.hintFrom &&
            hintTo == other.hintTo &&
            whiteTimeMs == other.whiteTimeMs &&
            blackTimeMs == other.blackTimeMs &&
            hasTimer == other.hasTimer &&
            thinking == other.thinking &&
            overlay == other.overlay &&
            inProgress == other.inProgress &&
            botLabel == other.botLabel &&
            canUndo == other.canUndo
    }

    override fun hashCode(): Int {
        var result = occupancy.contentHashCode()
        result = 31 * result + playerIsWhite.hashCode()
        result = 31 * result + (selected ?: 0)
        result = 31 * result + targets.hashCode()
        result = 31 * result + (lastFrom ?: 0)
        result = 31 * result + (lastTo ?: 0)
        result = 31 * result + (hintFrom ?: 0)
        result = 31 * result + (hintTo ?: 0)
        result = 31 * result + whiteTimeMs.hashCode()
        result = 31 * result + blackTimeMs.hashCode()
        result = 31 * result + hasTimer.hashCode()
        result = 31 * result + thinking.hashCode()
        result = 31 * result + overlay.hashCode()
        result = 31 * result + inProgress.hashCode()
        result = 31 * result + botLabel.hashCode()
        result = 31 * result + canUndo.hashCode()
        return result
    }
}
