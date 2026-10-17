package dev.neoneon.flamingo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightColorsPreviewProvider
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import dev.neoneon.chesskit.Move
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The board turns to face the next player by fading out, flipping while it can't be seen, and
// fading back in: a beat first, so both players see the move land, then one second in all.
private const val TURN_DELAY_MS = 800
private const val TURN_FADE_MS = 500

/**
 * The hot seat game — "Local" to the player: two people, one phone, the board turning to face
 * whoever moves next. It never touches the server, so it works whatever the service status says.
 *
 * The rules live in [HotSeatGameModel]; this keeps the selection, the panes and the saving.
 */
class HotSeatViewModel(dataStore: DataStore<Preferences>) : LightViewModel<Unit>() {
    // Built here rather than injected, like GamesListViewModel's: the model is an internal type,
    // and a public constructor may not name one.
    private val store: HotSeatGameStore = DataStoreHotSeatGameStore(dataStore)
    private val archive: HotSeatArchiveStore = DataStoreHotSeatArchiveStore(dataStore)
    private val lastMoveVisibilityStore = LastMoveVisibilityStore(dataStore)
    private var model: HotSeatGameModel? = null

    /** `null` until the first move: opening the screen writes nothing on its own. */
    private var game: HotSeatGame? = null

    /** One change to what is stored. */
    private sealed interface Write {
        data class Save(val game: HotSeatGame) : Write
        data object Clear : Write

        /** Moves [archived] into the archive and off the board. */
        data class Archive(val archived: ArchivedHotSeatGame) : Write
    }

    // One writer, so writes land in the order they were made. Unbounded rather than conflated: a
    // conflated channel would let the first move of the next game swallow an archive write still
    // waiting its turn, and moves come at a human pace, so there is no burst worth collapsing.
    private val writes = Channel<Write>(Channel.UNLIMITED)

    /** What is laid over the board. The promotion picker isn't one: [State.promotion] drives it. */
    enum class Pane { None, Actions, ConfirmingReset, ConfirmingSave }

    data class State(
        val isLoading: Boolean = true,
        val position: Position = Position.standard,
        /** The color at the bottom of the board. Trails [sideToMove] until the board has turned. */
        val orientation: Piece.Color = Piece.Color.white,
        val sideToMove: Piece.Color = Piece.Color.white,
        val selectedSquare: Square? = null,
        val legalTargets: Set<Square> = emptySet(),
        val lastMove: Pair<Square, Square>? = null,
        val checkedKingSquare: Square? = null,
        val outcome: GameOutcome? = null,
        /** A pawn on the last rank waiting for its piece. The picker is up while this is set. */
        val promotion: Move? = null,
        val pane: Pane = Pane.None,
        val canTakeBack: Boolean = false,
        val canSaveToArchive: Boolean = false,
        /** The board faces the player who just moved; the screen turns it. */
        val needsTurn: Boolean = false,
        /**
         * Bumped by everything that can leave the board facing the wrong player — a move, a
         * take-back, a reset — so the screen knows to look at [needsTurn] again.
         */
        val turnKey: Int = 0,
        // Read reactively, as GameView does, so a change on the settings screen reaches this one.
        val lastMoveVisibility: LastMoveVisibility = LastMoveVisibility.OpponentOnly,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            lastMoveVisibilityStore.flow.collect { visibility ->
                _state.update { it.copy(lastMoveVisibility = visibility) }
            }
        }
        viewModelScope.launch {
            for (write in writes) {
                when (write) {
                    is Write.Save -> store.save(write.game)
                    Write.Clear -> store.clear()
                    // Archived first, cleared second: a tool killed in between leaves the game in
                    // both places rather than in neither, and saving it again replaces the
                    // archived copy by its id.
                    is Write.Archive -> {
                        archive.add(write.archived)
                        store.clear()
                    }
                }
            }
        }
        viewModelScope.launch {
            val stored = store.load()
            val loaded = HotSeatGameModel(stored?.moves.orEmpty())
            game = stored?.copy(moves = loaded.moves)
            if (loaded.wasTrimmed) game?.let { writes.trySend(Write.Save(it)) }
            model = loaded
            publish { copy(isLoading = false) }
        }
    }

    fun onSquareTapped(square: Square) {
        val model = model ?: return
        val current = _state.value
        // The board only takes taps while it is the thing on screen and still being played.
        if (current.pane != Pane.None || model.pendingPromotion != null || model.outcome != null) return
        val selected = current.selectedSquare

        when {
            selected == square -> publish { copy(selectedSquare = null, legalTargets = emptySet()) }

            selected != null && square in current.legalTargets -> {
                // A pawn reaching the last rank isn't recorded yet: the picker comes up instead.
                val recorded = model.play(selected, square)
                if (recorded) save()
                publish {
                    copy(
                        selectedSquare = null,
                        legalTargets = emptySet(),
                        turnKey = if (recorded) turnKey + 1 else turnKey,
                    )
                }
            }

            model.position.piece(at = square)?.color == model.sideToMove -> publish {
                copy(selectedSquare = square, legalTargets = model.legalTargets(square))
            }

            else -> publish { copy(selectedSquare = null, legalTargets = emptySet()) }
        }
    }

    fun onPromotionSelected(kind: Piece.Kind) {
        val model = model ?: return
        if (!model.completePromotion(kind)) return
        save()
        publish { copy(turnKey = turnKey + 1) }
    }

    /** Back out of the picker: the pawn goes back, since nothing was recorded for it. */
    fun cancelPromotion() {
        model?.cancelPromotion()
        publish()
    }

    fun openActions() {
        publish { copy(pane = Pane.Actions, selectedSquare = null, legalTargets = emptySet()) }
    }

    fun closeActions() {
        publish { copy(pane = Pane.None) }
    }

    /** Takes back the last move and goes back to the board, so the players see it turn back. */
    fun takeBack() {
        val model = model ?: return
        if (!model.canTakeBack) return
        if (model.takeBack()) save()
        publish { copy(pane = Pane.None, turnKey = turnKey + 1) }
    }

    /** Resetting throws the whole game away, so it asks first. */
    fun requestReset() {
        if (model?.canTakeBack != true) return
        publish { copy(pane = Pane.ConfirmingReset) }
    }

    fun cancelReset() {
        publish { copy(pane = Pane.Actions) }
    }

    fun confirmReset() {
        startOver(Write.Clear)
    }

    /** Saving to the archive takes the game off the board for good, so it asks first, as a reset does. */
    fun requestSaveToArchive() {
        if (model?.canSaveToArchive != true) return
        publish { copy(pane = Pane.ConfirmingSave) }
    }

    fun cancelSaveToArchive() {
        publish { copy(pane = Pane.Actions) }
    }

    /**
     * Moves the finished game into the archive and starts the board over. Moved rather than
     * copied: there is only one hot seat game, and a take-back on a game that is already archived
     * would leave two versions of it that disagree.
     */
    fun confirmSaveToArchive() {
        val model = model ?: return
        val outcome = model.outcome ?: return
        // A finished game has moves, so it has been saved at least once.
        val game = game ?: return
        startOver(
            Write.Archive(
                ArchivedHotSeatGame(
                    game = game.copy(moves = model.moves),
                    outcome = outcome.archived(),
                    archivedAtMillis = System.currentTimeMillis(),
                ),
            ),
        )
    }

    // Back to the start position once [write] has dealt with the game that was on the board. Still
    // facing as it was, so the players see the board turn back to white.
    private fun startOver(write: Write) {
        val model = model ?: return
        model.reset()
        game = null
        writes.trySend(write)
        publish { copy(pane = Pane.None, turnKey = turnKey + 1) }
    }

    /** The screen calls this with the board faded out, so the flip itself is never seen. */
    fun turnToSideToMove() {
        model?.turnToSideToMove()
        publish()
    }

    private fun save() {
        val model = model ?: return
        val updated = (game ?: HotSeatGame()).copy(moves = model.moves)
        game = updated
        writes.trySend(Write.Save(updated))
    }

    // Every change goes out through here, so the board on screen can't drift from the model.
    private fun publish(change: State.() -> State = { this }) {
        val model = model ?: return
        _state.update { model.snapshot(it.change()) }
    }

    override fun onCleared() {
        super.onCleared()
        writes.close()
    }
}

/** [base] with everything the model owns brought up to date. */
internal fun HotSeatGameModel.snapshot(base: HotSeatViewModel.State) = base.copy(
    position = position,
    orientation = orientation,
    sideToMove = sideToMove,
    lastMove = lastMoveSquares,
    checkedKingSquare = kingInCheck,
    outcome = outcome,
    promotion = pendingPromotion,
    canTakeBack = canTakeBack,
    canSaveToArchive = canSaveToArchive,
    needsTurn = needsTurn,
)

class HotSeatScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HotSeatViewModel>(sealedActivity) {

    override val viewModelClass: Class<HotSeatViewModel>
        get() = HotSeatViewModel::class.java

    override fun createViewModel() = HotSeatViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        HotSeatContent(
            state = state,
            colors = themeColors,
            actions = HotSeatActions(
                onBack = { goBack() },
                onSquareTap = { viewModel.onSquareTapped(it) },
                onPromotionSelected = { viewModel.onPromotionSelected(it) },
                onCancelPromotion = { viewModel.cancelPromotion() },
                onOpenActions = { viewModel.openActions() },
                onCloseActions = { viewModel.closeActions() },
                onTakeBack = { viewModel.takeBack() },
                onRequestReset = { viewModel.requestReset() },
                onCancelReset = { viewModel.cancelReset() },
                onConfirmReset = { viewModel.confirmReset() },
                onRequestSaveToArchive = { viewModel.requestSaveToArchive() },
                onCancelSaveToArchive = { viewModel.cancelSaveToArchive() },
                onConfirmSaveToArchive = { viewModel.confirmSaveToArchive() },
                onTurnToSideToMove = { viewModel.turnToSideToMove() },
            ),
        )
    }
}

/** The screen's callbacks, grouped as GameView's are; the no-op defaults let a preview pass state alone. */
private data class HotSeatActions(
    val onBack: () -> Unit = {},
    val onSquareTap: (Square) -> Unit = {},
    val onPromotionSelected: (Piece.Kind) -> Unit = {},
    val onCancelPromotion: () -> Unit = {},
    val onOpenActions: () -> Unit = {},
    val onCloseActions: () -> Unit = {},
    val onTakeBack: () -> Unit = {},
    val onRequestReset: () -> Unit = {},
    val onCancelReset: () -> Unit = {},
    val onConfirmReset: () -> Unit = {},
    val onRequestSaveToArchive: () -> Unit = {},
    val onCancelSaveToArchive: () -> Unit = {},
    val onConfirmSaveToArchive: () -> Unit = {},
    val onTurnToSideToMove: () -> Unit = {},
)

// Driven only by state and callbacks, so every state it can be in renders from a @Preview.
@Composable
private fun HotSeatContent(
    state: HotSeatViewModel.State,
    colors: LightColors,
    actions: HotSeatActions = HotSeatActions(),
) {
    // Held up here rather than with the board, so the actions button can wait for a turn too.
    val boardAlpha = remember { Animatable(1f) }
    var isTurning by remember { mutableStateOf(false) }

    LightTheme(colors = colors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            val promotion = state.promotion
            when {
                promotion != null -> PromotionPane(promotion = promotion, actions = actions)
                state.pane == HotSeatViewModel.Pane.Actions -> ActionsPane(state = state, actions = actions)
                state.pane == HotSeatViewModel.Pane.ConfirmingReset -> ConfirmResetPane(actions = actions)
                state.pane == HotSeatViewModel.Pane.ConfirmingSave -> ConfirmSavePane(actions = actions)
                else -> {
                    // Lives with the board, so a pane laid over it cancels the turn and putting the
                    // board back starts it again — which is what a take-back or reset from the
                    // actions pane needs.
                    LaunchedEffect(state.turnKey) {
                        if (!state.needsTurn) {
                            boardAlpha.snapTo(1f)
                            return@LaunchedEffect
                        }
                        isTurning = true
                        try {
                            boardAlpha.animateTo(0f, tween(TURN_FADE_MS, delayMillis = TURN_DELAY_MS))
                            actions.onTurnToSideToMove()
                            boardAlpha.animateTo(1f, tween(TURN_FADE_MS))
                        } finally {
                            isTurning = false
                        }
                    }
                    BoardContent(
                        state = state,
                        actions = actions,
                        isTurning = isTurning,
                        boardAlpha = { boardAlpha.value },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.BoardContent(
    state: HotSeatViewModel.State,
    actions: HotSeatActions,
    isTurning: Boolean,
    boardAlpha: () -> Float,
) {
    val outcome = state.outcome

    LightTopBar(
        leftButton = LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = actions.onBack,
            contentDescription = "Back to games",
        ),
        center = LightTopBarCenter.TwoLineDetail(
            line1 = "Local",
            line2 = when {
                outcome != null -> outcome.label
                state.sideToMove == Piece.Color.white -> "White to move"
                else -> "Black to move"
            },
        ),
        rightButton = LightBarButton.LightIcon(
            icon = LightIcons.ELLIPSES,
            // A pane opened mid-turn would leave the board half faded underneath it.
            onClick = { if (!isTurning) actions.onOpenActions() },
            contentDescription = "Game actions",
        ),
    )

    // Sized and pinned like GameView's board, so the two screens line up.
    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (state.isLoading) {
            LightText(
                text = "Loading…",
                variant = LightTextVariant.Copy,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            ChessBoard(
                position = state.position,
                selectedSquare = state.selectedSquare,
                legalTargets = state.legalTargets,
                // A move started on a board about to flip would land under the other player.
                onSquareTap = { if (!isTurning) actions.onSquareTap(it) },
                orientation = state.orientation,
                boardSize = minOf(maxWidth, maxHeight),
                checkedKingSquare = state.checkedKingSquare,
                // The player at the board is always the side to move, so the Board setting reads
                // as it does in a live game: "opponent's last move" is every last move here.
                lastMove = state.lastMove.takeIf {
                    outcome != null || shouldShowLiveLastMove(
                        visibility = state.lastMoveVisibility,
                        lastMove = state.lastMove,
                        position = state.position,
                        localColor = state.sideToMove,
                    )
                },
                // Read in the draw phase, so the fade doesn't recompose the board every frame.
                modifier = Modifier.graphicsLayer { alpha = boardAlpha() },
            )
        }
    }
}

@Composable
private fun ColumnScope.PromotionPane(promotion: Move, actions: HotSeatActions) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = actions.onCancelPromotion,
            contentDescription = "Take the pawn back",
        ),
        center = LightTopBarCenter.Text("Promote to"),
    )
    PromotionOptions(
        color = promotion.piece.color,
        square = promotion.end,
        onSelect = actions.onPromotionSelected,
    )
}

@Composable
private fun ColumnScope.ActionsPane(state: HotSeatViewModel.State, actions: HotSeatActions) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = actions.onCloseActions,
            contentDescription = "Back to game",
        ),
        center = LightTopBarCenter.Text("Game actions"),
    )
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        ActionRow(
            label = "Save to archive",
            enabled = state.canSaveToArchive,
            onClick = actions.onRequestSaveToArchive,
        )
        ActionRow(label = "Take back move", enabled = state.canTakeBack, onClick = actions.onTakeBack)
        ActionRow(label = "Reset board", enabled = state.canTakeBack, onClick = actions.onRequestReset)
    }
}

@Composable
private fun ColumnScope.ConfirmResetPane(actions: HotSeatActions) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = actions.onCancelReset,
            contentDescription = "Back to game actions",
        ),
        center = LightTopBarCenter.Text("Reset board?"),
    )
    CenteredMessage("This clears the local game.\nIt can't be undone.")
    LightBottomBar(
        items = listOf(
            LightBarButton.Text(text = "CANCEL", onClick = actions.onCancelReset),
            LightBarButton.Text(text = "RESET", onClick = actions.onConfirmReset),
        ),
    )
}

@Composable
private fun ColumnScope.ConfirmSavePane(actions: HotSeatActions) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = actions.onCancelSaveToArchive,
            contentDescription = "Back to game actions",
        ),
        center = LightTopBarCenter.Text("Save to archive?"),
    )
    CenteredMessage("The game moves to the archive,\nwhere you can replay it.\nThe board starts over.")
    LightBottomBar(
        items = listOf(
            LightBarButton.Text(text = "CANCEL", onClick = actions.onCancelSaveToArchive),
            LightBarButton.Text(text = "SAVE", onClick = actions.onConfirmSaveToArchive),
        ),
    )
}

// MARK: Previews

// Built by playing real moves through the model, so the previews can't drift from the screen.
private fun previewState(
    moves: List<String> = emptyList(),
    pane: HotSeatViewModel.Pane = HotSeatViewModel.Pane.None,
    then: HotSeatGameModel.() -> Unit = {},
): HotSeatViewModel.State {
    val model = HotSeatGameModel(moves).apply(then)
    return model.snapshot(HotSeatViewModel.State(isLoading = false, pane = pane))
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHotSeatFresh(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    HotSeatContent(state = previewState(), colors = colors)
}

// After 1.e4, once the board has turned: black at the bottom, e2 and e4 shaded.
@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHotSeatBlackToMove(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    HotSeatContent(state = previewState(listOf("e2e4")), colors = colors)
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHotSeatPromoting(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    val beforePromotion = listOf("h2h4", "g7g5", "h4g5", "h7h6", "g5h6", "f8g7", "h6g7", "g8f6")
    HotSeatContent(
        state = previewState(beforePromotion) { play(Square("g7"), Square("h8")) },
        colors = colors,
    )
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHotSeatActions(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    HotSeatContent(state = previewState(listOf("e2e4"), pane = HotSeatViewModel.Pane.Actions), colors = colors)
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHotSeatConfirmReset(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    HotSeatContent(
        state = previewState(listOf("e2e4"), pane = HotSeatViewModel.Pane.ConfirmingReset),
        colors = colors,
    )
}

// Over, so "Save to archive" is on offer.
@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHotSeatActionsFinished(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    HotSeatContent(
        state = previewState(listOf("f2f3", "e7e5", "g2g4", "d8h4"), pane = HotSeatViewModel.Pane.Actions),
        colors = colors,
    )
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHotSeatConfirmSave(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    HotSeatContent(
        state = previewState(listOf("f2f3", "e7e5", "g2g4", "d8h4"), pane = HotSeatViewModel.Pane.ConfirmingSave),
        colors = colors,
    )
}

// Fool's mate: "Checkmate, black won", the board facing white — the side to move, now mated.
@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHotSeatCheckmate(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    HotSeatContent(state = previewState(listOf("f2f3", "e7e5", "g2g4", "d8h4")), colors = colors)
}
