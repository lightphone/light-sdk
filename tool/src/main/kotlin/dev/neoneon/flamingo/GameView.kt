package dev.neoneon.flamingo

import android.content.ClipData
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Move
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "GameView"

class GameViewViewModel(
    val gameId: String,
    private val identityStore: PlayerIdentityStore,
) : LightViewModel<Unit>() {
    private var board = Board()
    private val api = FlamingoApi()
    private val transport: LiveTransport = KtorLiveTransport(viewModelScope)
    private var moveCount = 0
    private var hasLoadedInitialState = false

    data class State(
        val position: Position,
        val selectedSquare: Square? = null,
        val legalTargets: Set<Square> = emptySet(),
        val isLoading: Boolean = true,
        val playerId: String? = null,
        val moveCount: Int = 0,
        val lastMovePreFen: String? = null,
        val lastMoveLan: String? = null,
    )

    private val _state = MutableStateFlow(State(position = board.position))
    val state: StateFlow<State> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (!hasLoadedInitialState) {
            hasLoadedInitialState = true
            observeLiveEvents()
            // Connect first so the socket is live before taps are enabled (the board stays
            // in its "Loading…" state, which blocks taps, until loadExistingGame finishes) —
            // this guarantees even white's first move goes out over the socket.
            viewModelScope.launch(Dispatchers.IO) {
                connect()
                loadExistingGame()
            }
        } else {
            // Returning to foreground: if we were waiting for the opponent, a move may have
            // landed while we were backgrounded — re-sync before reconnecting the socket.
            viewModelScope.launch(Dispatchers.IO) {
                if (isWaitingForOpponent()) loadExistingGame()
                connect()
            }
        }
    }

    override fun onAppPause() {
        super.onAppPause()
        // Drop the live connection while backgrounded; it's re-established on the next show.
        viewModelScope.launch { transport.disconnect() }
    }

    // Loads and replays this game's recorded moves so resuming an in-progress game
    // continues from its real current position instead of the initial one. A brand
    // new game has no server record yet, so a failure here just means "start fresh".
    // Safe to call again (foreground re-sync): the board is rebuilt from scratch.
    private suspend fun loadExistingGame() {
        val playerId = identityStore.getOrCreate()
        var lastMove: dev.neoneon.flamingo.Move? = null
        api.fetchGame(gameId).onSuccess { detail ->
            val sorted = detail.moves.sortedBy { it.moveNumber }
            board = Board()
            sorted.forEach { replayMove(it) }
            lastMove = sorted.lastOrNull()
            moveCount = sorted.maxOfOrNull { it.moveNumber } ?: 0
        }.onFailure { error ->
            Log.w(TAG, "No existing state loaded for game $gameId, starting fresh", error)
        }
        _state.update {
            it.copy(
                position = board.position,
                isLoading = false,
                playerId = playerId,
                moveCount = moveCount,
                // fenAfter is misleadingly named — it's the fen the move was played
                // *from*, matching the pre-move `fen` this backend always stores.
                lastMovePreFen = lastMove?.fenAfter,
                lastMoveLan = lastMove?.lan,
            )
        }
    }

    private suspend fun connect() {
        val playerId = _state.value.playerId ?: identityStore.getOrCreate()
        transport.connect(gameId, playerId)
    }

    // The Kotlin tool is always white, so "waiting for opponent" is simply "black to move".
    private fun isWaitingForOpponent(): Boolean =
        _state.value.position.sideToMove != Piece.Color.white

    // Collects inbound live actions for the lifetime of the screen. Runs on the main
    // dispatcher, the same context as onSquareTapped, so board mutations stay serialized.
    private fun observeLiveEvents() {
        viewModelScope.launch {
            transport.events.collect { event ->
                when (event) {
                    is LiveEvent.Action -> applyIncoming(event.action)
                    LiveEvent.NeedsResync -> {
                        // Divergence, or the socket dropped — re-sync from the server, then
                        // reconnect (after a short backoff to avoid hammering a bad connection).
                        loadExistingGame()
                        delay(1000)
                        connect()
                    }
                }
            }
        }
    }

    // Applies the opponent's live move to the board so white sees it appear immediately.
    // The server never echoes our own moves back, so this only ever runs for black's moves.
    private fun applyIncoming(action: LiveAction) {
        if (action.intent != LiveAction.INTENT_MOVE) return
        val lan = action.lan ?: return
        if (lan.length < 4) return

        val from = Square(lan.substring(0, 2))
        val to = Square(lan.substring(2, 4))
        val move = board.move(pieceAt = from, to = to) ?: return
        if (lan.length == 5 && board.state is Board.State.promotion) {
            Piece.Kind.fromRawValue(lan.last().uppercase())?.let { kind ->
                board.completePromotion(move, kind)
            }
        }
        action.n?.let { if (it > moveCount) moveCount = it }
        _state.update {
            it.copy(
                position = board.position,
                selectedSquare = null,
                legalTargets = emptySet(),
                moveCount = moveCount,
            )
        }
    }

    // Applies a previously recorded move's LAN to [board], completing promotions
    // where needed. Draw-offer/resign log entries carry no LAN and are skipped.
    private fun replayMove(stored: dev.neoneon.flamingo.Move) {
        val lan = stored.lan
        if (lan.length < 4) return

        val from = Square(lan.substring(0, 2))
        val to = Square(lan.substring(2, 4))
        val move = board.move(pieceAt = from, to = to) ?: return

        if (lan.length == 5 && board.state is Board.State.promotion) {
            Piece.Kind.fromRawValue(lan.last().uppercase())?.let { kind ->
                board.completePromotion(move, kind)
            }
        }
    }

    // Kotlin only ever creates games (it's always white) — black is a remote player
    // now, so local taps can never move for it, regardless of whose turn it is.
    fun onSquareTapped(square: Square) {
        _state.update { current ->
            val selected = current.selectedSquare

            when {
                current.isLoading || current.position.sideToMove != Piece.Color.white -> current

                selected == square -> current.copy(selectedSquare = null, legalTargets = emptySet())

                selected != null && square in current.legalTargets -> {
                    val preMoveFen = board.position.fen
                    val move = board.move(pieceAt = selected, to = square)
                    if (move != null) submitMove(move, preMoveFen, current.playerId)
                    current.copy(
                        position = board.position,
                        selectedSquare = null,
                        legalTargets = emptySet(),
                        moveCount = moveCount,
                        lastMovePreFen = if (move != null) preMoveFen else current.lastMovePreFen,
                        lastMoveLan = move?.lan ?: current.lastMoveLan,
                    )
                }

                current.position.piece(at = square)?.color == current.position.sideToMove -> {
                    current.copy(
                        selectedSquare = square,
                        legalTargets = board.legalMoves(forPieceAt = square).toSet(),
                    )
                }

                else -> current.copy(selectedSquare = null, legalTargets = emptySet())
            }
        }
    }

    // Sends the local move over the live socket instead of a separate HTTP request.
    // The server records it (creating the game lazily on move 1, since the tool is
    // always white) and broadcasts it to the opponent — producing the same game
    // history as the static HTTP variant. The pre-move `fen` is sent so the server
    // stores the FEN each move was played *from*, matching the existing protocol.
    private fun submitMove(move: Move, preMoveFen: String, playerId: String?) {
        moveCount += 1
        val moveNumber = moveCount

        viewModelScope.launch(Dispatchers.IO) {
            val callerPlayerId = playerId ?: identityStore.getOrCreate()
            transport.send(
                LiveAction(
                    intent = LiveAction.INTENT_MOVE,
                    player = callerPlayerId,
                    lan = move.lan,
                    fen = preMoveFen,
                    n = moveNumber,
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        transport.close()
        api.close()
    }
}

// (playerId, preMoveFen, lan) for the invite URL, or null until there's a move to share.
private fun shareParams(state: GameViewViewModel.State): Triple<String, String, String>? {
    val playerId = state.playerId ?: return null
    val preMoveFen = state.lastMovePreFen ?: return null
    val lan = state.lastMoveLan ?: return null
    return Triple(playerId, preMoveFen, lan)
}

class GameView(
    sealedActivity: SealedLightActivity,
    private val gameId: String,
) : LightScreen<Unit, GameViewViewModel>(sealedActivity) {

    override val viewModelClass: Class<GameViewViewModel>
        get() = GameViewViewModel::class.java

    override fun createViewModel() = GameViewViewModel(gameId, PlayerIdentityStore(lightContext.dataStore))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val clipboard = LocalClipboard.current
        val coroutineScope = rememberCoroutineScope()
        var justCopied by remember { mutableStateOf(false) }

        if (justCopied) {
            LaunchedEffect(Unit) {
                delay(1500)
                justCopied = false
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back to games",
                    ),
                    center = LightTopBarCenter.Text("Game"),
                    // Sharing before white's first move would invite black into a game
                    // with no move to apply — same rule as the iMessage send button
                    // (CLAUDE.md: sendButtonIsDisabled is driven by lastMoveLAN != nil).
                    rightButton = shareParams(state)?.let { (playerId, preMoveFen, lan) ->
                        LightBarButton.Icon(
                            painter = rememberVectorPainter(if (justCopied) Icons.Default.Check else Icons.Default.Share),
                            onClick = {
                                val url = buildInviteUrl(viewModel.gameId, preMoveFen, lan, playerId)
                                coroutineScope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Game invite", url.toString())))
                                }
                                justCopied = true
                            },
                            contentDescription = "Copy invite link",
                        )
                    },
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isLoading) {
                        LightText(text = "Loading…", variant = LightTextVariant.Copy)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (state.position.sideToMove != Piece.Color.white) {
                                LightText(text = "Waiting for opponent…", variant = LightTextVariant.Copy)
                            }
                            // Tapping a square is a no-op while it's not white's turn
                            // (GameViewViewModel.onSquareTapped guards on sideToMove),
                            // so the board can stay on screen instead of being swapped for text.
                            ChessBoard(
                                position = state.position,
                                selectedSquare = state.selectedSquare,
                                legalTargets = state.legalTargets,
                                onSquareTap = { viewModel.onSquareTapped(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}
