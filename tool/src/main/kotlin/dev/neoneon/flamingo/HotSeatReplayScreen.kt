package dev.neoneon.flamingo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightColorsPreviewProvider
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * An archived local game, stepped through from its moves. Opens on the final position, the way a
 * finished online game does, with white at the bottom: no one player sat at this board.
 *
 * Mirrors iOS's `HotSeatReplayView`. The screen's result is the id of a game to delete — the
 * archive screen does the deleting, since it holds the list the game has to leave.
 */
class HotSeatReplayViewModel(archived: ArchivedHotSeatGame) : LightViewModel<String>() {
    enum class Pane { None, ConfirmingDelete }

    data class State(
        val position: Position,
        val lastMove: Pair<Square, Square>?,
        val checkedKingSquare: Square?,
        val frameIndex: Int,
        val frameCount: Int,
        val outcome: ArchivedOutcome,
        val pane: Pane = Pane.None,
    ) {
        val canStepBack: Boolean get() = frameIndex > 0

        val canStepForward: Boolean get() = frameIndex < frameCount - 1

        /** The result on the final position; once the player steps off it, where they are instead. */
        val statusLine: String
            get() = if (canStepForward) replayPositionLabel(frameIndex, frameCount) else outcome.label
    }

    private val outcome = archived.outcome
    private val replay = ReplayLog().apply { archived.game.replayFrames().forEach { record(it) } }

    private val _state = MutableStateFlow(replay.replayState(outcome))
    val state: StateFlow<State> = _state

    /** Steps one position back or forward, stopping at either end. */
    fun onStepReplay(forward: Boolean) {
        if (!replay.step(forward)) return
        _state.update { replay.replayState(outcome, it.pane) }
    }

    /** Deleting can't be undone, so it asks first. */
    fun requestDelete() {
        _state.update { it.copy(pane = Pane.ConfirmingDelete) }
    }

    fun cancelDelete() {
        _state.update { it.copy(pane = Pane.None) }
    }
}

/** The frame [this] log stands on, with what the screen needs to step from it. */
internal fun ReplayLog.replayState(
    outcome: ArchivedOutcome,
    pane: HotSeatReplayViewModel.Pane = HotSeatReplayViewModel.Pane.None,
): HotSeatReplayViewModel.State {
    val frame = current ?: ReplayFrame(position = Position.standard)
    return HotSeatReplayViewModel.State(
        position = frame.position,
        lastMove = frame.lastMove,
        checkedKingSquare = frame.checkedKingSquare,
        frameIndex = index,
        frameCount = size,
        outcome = outcome,
        pane = pane,
    )
}

class HotSeatReplayScreen(
    sealedActivity: SealedLightActivity,
    private val archived: ArchivedHotSeatGame,
) : LightScreen<String, HotSeatReplayViewModel>(sealedActivity) {

    override val viewModelClass: Class<HotSeatReplayViewModel>
        get() = HotSeatReplayViewModel::class.java

    override fun createViewModel() = HotSeatReplayViewModel(archived)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        HotSeatReplayContent(
            state = state,
            colors = themeColors,
            actions = HotSeatReplayActions(
                onBack = { goBack() },
                onStepReplay = { viewModel.onStepReplay(it) },
                onRequestDelete = { viewModel.requestDelete() },
                onCancelDelete = { viewModel.cancelDelete() },
                onConfirmDelete = { goBack(archived.id) },
            ),
        )
    }
}

/** The screen's callbacks; the no-op defaults let a preview pass state alone. */
private data class HotSeatReplayActions(
    val onBack: () -> Unit = {},
    val onStepReplay: (forward: Boolean) -> Unit = {},
    val onRequestDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
)

@Composable
private fun HotSeatReplayContent(
    state: HotSeatReplayViewModel.State,
    colors: LightColors,
    actions: HotSeatReplayActions = HotSeatReplayActions(),
) {
    LightTheme(colors = colors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            when (state.pane) {
                HotSeatReplayViewModel.Pane.None -> ReplayBoard(state = state, actions = actions)
                HotSeatReplayViewModel.Pane.ConfirmingDelete -> ConfirmDeletePane(actions = actions)
            }
        }
    }
}

@Composable
private fun ColumnScope.ReplayBoard(state: HotSeatReplayViewModel.State, actions: HotSeatReplayActions) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = actions.onBack,
            contentDescription = "Back to archive",
        ),
        center = LightTopBarCenter.TwoLineDetail(line1 = "Local", line2 = state.statusLine),
        rightButton = LightBarButton.LightIcon(
            icon = LightIcons.TRASH,
            onClick = actions.onRequestDelete,
            contentDescription = "Delete game",
        ),
    )

    // Sized and pinned like GameView's board, so the two replays line up.
    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        ChessBoard(
            position = state.position,
            selectedSquare = null,
            legalTargets = emptySet(),
            onSquareTap = {},
            boardSize = minOf(maxWidth, maxHeight),
            checkedKingSquare = state.checkedKingSquare,
            lastMove = state.lastMove,
        )
    }

    // As in GameView: a button with nowhere to go is left as an empty slot, so the other one stays
    // where it was.
    LightBottomBar(
        items = listOf(
            LightBarButton.Text(text = "PREV", onClick = { actions.onStepReplay(false) })
                .takeIf { state.canStepBack },
            LightBarButton.Text(text = "NEXT", onClick = { actions.onStepReplay(true) })
                .takeIf { state.canStepForward },
        ),
    )
}

@Composable
private fun ColumnScope.ConfirmDeletePane(actions: HotSeatReplayActions) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = actions.onCancelDelete,
            contentDescription = "Back to game",
        ),
        center = LightTopBarCenter.Text("Delete game?"),
    )
    CenteredMessage("The game leaves the archive.\nIt can't be undone.")
    LightBottomBar(
        items = listOf(
            LightBarButton.Text(text = "CANCEL", onClick = actions.onCancelDelete),
            LightBarButton.Text(text = "DELETE", onClick = actions.onConfirmDelete),
        ),
    )
}

// MARK: Previews

// Fool's mate, replayed through the same code the screen uses.
private fun previewState(
    stepsBack: Int = 0,
    pane: HotSeatReplayViewModel.Pane = HotSeatReplayViewModel.Pane.None,
): HotSeatReplayViewModel.State {
    val game = HotSeatGame(moves = listOf("f2f3", "e7e5", "g2g4", "d8h4"))
    val replay = ReplayLog().apply { game.replayFrames().forEach { record(it) } }
    repeat(stepsBack) { replay.step(forward = false) }
    return replay.replayState(ArchivedOutcome.BlackWins, pane)
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHotSeatReplayFinal(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    HotSeatReplayContent(state = previewState(), colors = colors)
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHotSeatReplaySteppedBack(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    HotSeatReplayContent(state = previewState(stepsBack = 2), colors = colors)
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewHotSeatReplayConfirmDelete(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    HotSeatReplayContent(
        state = previewState(pane = HotSeatReplayViewModel.Pane.ConfirmingDelete),
        colors = colors,
    )
}
