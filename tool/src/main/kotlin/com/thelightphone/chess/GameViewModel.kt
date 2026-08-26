package com.thelightphone.chess

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.thelightphone.chess.engine.BISHOP
import com.thelightphone.chess.engine.Board
import com.thelightphone.chess.engine.BotLevel
import com.thelightphone.chess.engine.ChessMove
import com.thelightphone.chess.engine.EMPTY
import com.thelightphone.chess.engine.GameResult
import com.thelightphone.chess.engine.KNIGHT
import com.thelightphone.chess.engine.QUEEN
import com.thelightphone.chess.engine.ROOK
import com.thelightphone.chess.engine.Search
import com.thelightphone.chess.engine.StartColor
import com.thelightphone.chess.engine.WHITE
import com.thelightphone.chess.engine.colorOf
import com.thelightphone.chess.engine.moveFrom
import com.thelightphone.chess.engine.moveTo
import com.thelightphone.chess.engine.sq64
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class GameViewModel(
    private val store: GameStore,
    private val launch: GameLaunch,
) : LightViewModel<Unit>() {

    private val board = Board.start()
    private val rng = Random.Default
    private val cancelSearch = AtomicBoolean(false)
    private var playerIsWhite: Boolean = true
    private var botLevel: BotLevel = BotLevel.MEDIUM
    private var timerMs: Long? = null
    private var whiteTimeMs: Long = 0
    private var blackTimeMs: Long = 0
    private var startFen: String = Board.START_FEN
    private val movesUci = mutableListOf<String>()
    private var result: GameResult? = null
    private var lastFrom: Int? = null
    private var lastTo: Int? = null
    private var lastTickElapsed: Long = 0
    private var clockJob: Job? = null
    private var started: Boolean = false

    private val _ui = MutableStateFlow(GameUiState())
    val ui: StateFlow<GameUiState> = _ui.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (!started) {
            started = true
            viewModelScope.launch { startGame() }
        } else if (result == null) {
            lastTickElapsed = SystemClock.elapsedRealtime()
            startClock()
            publish()
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        clockJob?.cancel()
        viewModelScope.launch { persist() }
    }

    override fun onAppPause() {
        super.onAppPause()
        clockJob?.cancel()
        viewModelScope.launch { persist() }
    }

    override fun onCleared() {
        cancelSearch.set(true)
        clockJob?.cancel()
        super.onCleared()
    }

    fun onSquare(sq: Int) {
        val state = _ui.value
        if (!state.inProgress || state.thinking) return
        if (state.overlay !is GameOverlay.None) return
        if (board.whiteToMove != playerIsWhite) return

        val selected = state.selected
        if (selected != null && sq in state.targets) {
            val options = board.legalMovesFrom(selected).filter { it.to == sq }
            if (options.size > 1 && options.any { it.isPromotion }) {
                _ui.update { it.copy(overlay = GameOverlay.Promotion(selected, sq), selected = selected) }
                return
            }
            val move = options.firstOrNull() ?: return
            playUserMove(move)
            return
        }

        val piece = board.squares[sq]
        val own = piece != EMPTY && (colorOf(piece) == WHITE) == playerIsWhite
        if (own) {
            val targets = board.legalMovesFrom(sq).map { it.to }.toSet()
            _ui.update { it.copy(selected = sq, targets = targets) }
        } else {
            _ui.update { it.copy(selected = null, targets = emptySet()) }
        }
    }

    fun promote(pieceType: Int) {
        val overlay = _ui.value.overlay as? GameOverlay.Promotion ?: return
        val move = board.legalMovesFrom(overlay.from).firstOrNull {
            it.to == overlay.to && it.promotion == pieceType
        } ?: return
        _ui.update { it.copy(overlay = GameOverlay.None) }
        playUserMove(move)
    }

    fun cancelPromotion() {
        _ui.update { it.copy(overlay = GameOverlay.None, selected = null, targets = emptySet()) }
    }

    fun requestResign() {
        if (!_ui.value.inProgress) return
        clockJob?.cancel()
        _ui.update { it.copy(overlay = GameOverlay.ResignConfirm) }
    }

    fun confirmResign() {
        result = GameResult.LOSS
        viewModelScope.launch {
            store.clear()
            publish(overlay = GameOverlay.GameOver("Resigned"))
        }
    }

    fun cancelResign() {
        _ui.update { it.copy(overlay = GameOverlay.None) }
        lastTickElapsed = SystemClock.elapsedRealtime()
        startClock()
        publish()
    }

    fun dismissGameOver() {
        _ui.update { it.copy(overlay = GameOverlay.None) }
    }

    private suspend fun startGame() {
        when (launch) {
            is GameLaunch.Continue -> {
                val saved = store.load()
                if (saved == null || !saved.isInProgress) {
                    result = GameResult.DRAW
                    publish(overlay = GameOverlay.GameOver("No saved game"))
                    return
                }
                restore(saved)
            }
            is GameLaunch.New -> {
                playerIsWhite = when (launch.color) {
                    StartColor.WHITE -> true
                    StartColor.BLACK -> false
                    StartColor.RANDOM -> rng.nextBoolean()
                }
                botLevel = launch.bot
                timerMs = launch.timer.durationMs
                whiteTimeMs = timerMs ?: 0
                blackTimeMs = timerMs ?: 0
                startFen = Board.START_FEN
                board.loadFen(startFen)
                movesUci.clear()
                result = null
                persist()
            }
        }
        lastTickElapsed = SystemClock.elapsedRealtime()
        startClock()
        publish()
        maybeComputerMove()
    }

    private fun restore(saved: SavedGame) {
        board.loadFen(saved.startFen)
        saved.movesUci.forEach { board.applyUci(it) }
        playerIsWhite = saved.playerIsWhite
        botLevel = saved.bot()
        timerMs = saved.timerMs
        whiteTimeMs = saved.whiteTimeMs
        blackTimeMs = saved.blackTimeMs
        startFen = saved.startFen
        movesUci.clear()
        movesUci.addAll(saved.movesUci)
        result = saved.outcome()
        if (saved.movesUci.isNotEmpty()) {
            val last = saved.movesUci.last()
            lastFrom = com.thelightphone.chess.engine.parseSquare(last.substring(0, 2))
            lastTo = com.thelightphone.chess.engine.parseSquare(last.substring(2, 4))
        }
        if (saved.clockRunningForWhite != null && timerMs != null && result == null) {
            val elapsed = SystemClock.elapsedRealtime() - saved.savedAtElapsedMs
            if (saved.clockRunningForWhite) {
                whiteTimeMs = (whiteTimeMs - elapsed).coerceAtLeast(0)
            } else {
                blackTimeMs = (blackTimeMs - elapsed).coerceAtLeast(0)
            }
            if (whiteTimeMs == 0L || blackTimeMs == 0L) {
                applyFlag()
            }
        }
    }

    private fun playUserMove(move: ChessMove) {
        applyMove(move.encoded)
        maybeComputerMove()
    }

    private fun applyMove(encoded: Int) {
        board.makeMove(encoded)
        movesUci += ChessMove.decode(encoded).uci()
        lastFrom = moveFrom(encoded)
        lastTo = moveTo(encoded)
        result = board.gameResult(playerIsWhite)
        viewModelScope.launch { persist() }
        if (result != null) {
            clockJob?.cancel()
            publish(overlay = GameOverlay.GameOver(resultMessage(result!!)))
        } else {
            publish()
        }
    }

    private fun maybeComputerMove() {
        if (result != null) return
        if (board.whiteToMove == playerIsWhite) return
        cancelSearch.set(false)
        _ui.update { it.copy(thinking = true, selected = null, targets = emptySet()) }
        viewModelScope.launch {
            val remaining = timerMs?.let {
                if (board.whiteToMove) whiteTimeMs else blackTimeMs
            }
            val level = botLevel
            val snapshot = board.copy()
            val move = withContext(Dispatchers.Default) {
                Search.pickMove(
                    board = snapshot,
                    level = level,
                    remainingMs = remaining,
                    rng = rng,
                    cancelled = { cancelSearch.get() },
                )
            }
            if (cancelSearch.get()) return@launch
            if (move != 0 && result == null) {
                applyMove(move)
            } else {
                publish()
            }
        }
    }

    private fun startClock() {
        clockJob?.cancel()
        if (timerMs == null || result != null) return
        lastTickElapsed = SystemClock.elapsedRealtime()
        clockJob = viewModelScope.launch {
            while (isActive && result == null) {
                delay(200)
                val now = SystemClock.elapsedRealtime()
                val dt = now - lastTickElapsed
                lastTickElapsed = now
                if (_ui.value.overlay is GameOverlay.ResignConfirm) continue
                if (board.whiteToMove) {
                    whiteTimeMs = (whiteTimeMs - dt).coerceAtLeast(0)
                    if (whiteTimeMs == 0L) applyFlag()
                } else {
                    blackTimeMs = (blackTimeMs - dt).coerceAtLeast(0)
                    if (blackTimeMs == 0L) applyFlag()
                }
                publish()
            }
        }
    }

    private fun applyFlag() {
        if (result != null) return
        val whiteFlagged = whiteTimeMs == 0L && board.whiteToMove
        val blackFlagged = blackTimeMs == 0L && !board.whiteToMove
        if (!whiteFlagged && !blackFlagged) return
        val whiteLost = whiteFlagged
        result = if ((whiteLost && playerIsWhite) || (!whiteLost && !playerIsWhite)) {
            GameResult.FLAG_LOSS
        } else {
            GameResult.FLAG_WIN
        }
        clockJob?.cancel()
        viewModelScope.launch {
            store.clear()
            publish(overlay = GameOverlay.GameOver(resultMessage(result!!)))
        }
    }

    private suspend fun persist() {
        if (result != null) {
            store.clear()
            return
        }
        store.save(
            SavedGame(
                fen = board.toFen(),
                startFen = startFen,
                movesUci = movesUci.toList(),
                playerIsWhite = playerIsWhite,
                timerMs = timerMs,
                whiteTimeMs = whiteTimeMs,
                blackTimeMs = blackTimeMs,
                clockRunningForWhite = if (timerMs != null) board.whiteToMove else null,
                savedAtElapsedMs = SystemClock.elapsedRealtime(),
                botLevel = botLevel.name,
                result = null,
            ),
        )
    }

    private fun publish(overlay: GameOverlay = _ui.value.overlay) {
        val occupancy = IntArray(64)
        for (sq in 0 until 128) {
            if (sq and 0x88 == 0) occupancy[sq64(sq)] = board.squares[sq]
        }
        val selected = _ui.value.selected
        val playerTurn = result == null && board.whiteToMove == playerIsWhite
        _ui.value = GameUiState(
            occupancy = occupancy,
            playerIsWhite = playerIsWhite,
            selected = selected,
            targets = if (selected != null && playerTurn) {
                board.legalMovesFrom(selected).map { it.to }.toSet()
            } else {
                emptySet()
            },
            lastFrom = lastFrom,
            lastTo = lastTo,
            whiteTimeMs = whiteTimeMs,
            blackTimeMs = blackTimeMs,
            hasTimer = timerMs != null,
            thinking = result == null && board.whiteToMove != playerIsWhite,
            overlay = overlay,
            inProgress = result == null,
            botLabel = botLevel.label,
        )
    }

    private fun resultMessage(outcome: GameResult): String = when (outcome) {
        GameResult.WIN -> "Checkmate — you win"
        GameResult.LOSS -> "Checkmate — you lose"
        GameResult.DRAW -> "Draw"
        GameResult.FLAG_WIN -> "Out of time — you win"
        GameResult.FLAG_LOSS -> "Out of time — you lose"
    }

    companion object {
        val PROMOTION_PIECES = listOf(
            QUEEN to "Queen",
            ROOK to "Rook",
            BISHOP to "Bishop",
            KNIGHT to "Knight",
        )
    }
}
