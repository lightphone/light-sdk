package dev.neoneon.flamingo

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
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
    private val gameId: String,
    private val identityStore: PlayerIdentityStore,
) : LightViewModel<Unit>() {
    private val board = Board()
    private val api = FlamingoApi()
    private var moveCount = 0
    private var hasLoadedInitialState = false

    data class State(
        val position: Position,
        val selectedSquare: Square? = null,
        val legalTargets: Set<Square> = emptySet(),
        val isLoading: Boolean = true,
    )

    private val _state = MutableStateFlow(State(position = board.position))
    val state: StateFlow<State> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (!hasLoadedInitialState) {
            hasLoadedInitialState = true
            loadExistingGame()
        }
    }

    // Loads and replays this game's recorded moves so resuming an in-progress game
    // continues from its real current position instead of the initial one. A brand
    // new game has no server record yet, so a failure here just means "start fresh".
    private fun loadExistingGame() {
        viewModelScope.launch(Dispatchers.IO) {
            api.fetchGame(gameId).onSuccess { detail ->
                detail.moves.sortedBy { it.moveNumber }.forEach { replayMove(it) }
                moveCount = detail.moves.maxOfOrNull { it.moveNumber } ?: 0
            }.onFailure { error ->
                Log.w(TAG, "No existing state loaded for game $gameId, starting fresh", error)
            }
            _state.update { it.copy(position = board.position, isLoading = false) }
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

    fun onSquareTapped(square: Square) {
        _state.update { current ->
            val selected = current.selectedSquare

            when {
                current.isLoading -> current

                selected == square -> current.copy(selectedSquare = null, legalTargets = emptySet())

                selected != null && square in current.legalTargets -> {
                    val preMoveFen = board.position.fen
                    val move = board.move(pieceAt = selected, to = square)
                    if (move != null) submitMove(move, preMoveFen)
                    current.copy(position = board.position, selectedSquare = null, legalTargets = emptySet())
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

    // The backend stores, for each move, the FEN it was played *from* — not the
    // resulting position — matching the pre-move `fen` the iOS client sends
    // alongside a move over MSMessage (see ChessBoardModel.preMovePosition).
    private fun submitMove(move: Move, preMoveFen: String) {
        moveCount += 1
        val moveNumber = moveCount

        viewModelScope.launch(Dispatchers.IO) {
            val identity = identityStore.getOrCreate()
            val callerPlayerId = if (move.piece.color == Piece.Color.white) {
                identity.whitePlayerId
            } else {
                identity.blackPlayerId
            }

            val result = if (moveNumber == 1) {
                api.createGame(
                    gameId = gameId,
                    lan = move.lan,
                    san = move.san,
                    fen = preMoveFen,
                    moveNumber = moveNumber,
                    whitePlayerId = identity.whitePlayerId,
                    callerPlayerId = callerPlayerId,
                )
            } else {
                api.recordMove(
                    gameId = gameId,
                    lan = move.lan,
                    san = move.san,
                    fen = preMoveFen,
                    moveNumber = moveNumber,
                    callerPlayerId = callerPlayerId,
                    whitePlayerId = identity.whitePlayerId,
                )
            }

            result.onFailure { error ->
                Log.e(TAG, "Failed to submit move $moveNumber for game $gameId", error)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
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
