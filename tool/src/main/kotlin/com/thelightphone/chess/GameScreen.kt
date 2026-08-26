package com.thelightphone.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.chess.engine.BLACK
import com.thelightphone.chess.engine.WHITE
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

class GameScreen(
    sealedActivity: SealedLightActivity,
    private val launch: GameLaunch,
) : LightScreen<Unit, GameViewModel>(sealedActivity) {

    override val viewModelClass: Class<GameViewModel>
        get() = GameViewModel::class.java

    override fun createViewModel() = GameViewModel(GameStore(lightContext.dataStore), launch)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.ui.collectAsState()

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                        ),
                        center = LightTopBarCenter.Text(gameTitle(state)),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    ChessBoard(
                        occupancy = state.occupancy,
                        playerIsWhite = state.playerIsWhite,
                        selected = state.selected,
                        targets = state.targets,
                        lastFrom = state.lastFrom,
                        lastTo = state.lastTo,
                        hintFrom = state.hintFrom,
                        hintTo = state.hintTo,
                        enabled = state.inProgress && !state.thinking && state.overlay is GameOverlay.None,
                        onSquare = viewModel::onSquare,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    )

                    LightBottomBar(
                        items = listOf(
                            LightBarButton.LightIcon(
                                icon = LightIcons.STAR,
                                contentDescription = "Hint",
                                onClick = if (state.inProgress &&
                                    !state.thinking &&
                                    state.overlay is GameOverlay.None
                                ) {
                                    { viewModel.requestHint() }
                                } else {
                                    null
                                },
                            ),
                            LightBarButton.LightIcon(
                                icon = LightIcons.TRASH,
                                contentDescription = "Resign",
                                onClick = if (state.inProgress && state.overlay is GameOverlay.None) {
                                    { viewModel.requestResign() }
                                } else {
                                    null
                                },
                            ),
                            LightBarButton.LightIcon(
                                icon = LightIcons.REWIND,
                                contentDescription = "Undo",
                                onClick = if (state.canUndo &&
                                    state.overlay !is GameOverlay.ResignConfirm
                                ) {
                                    { viewModel.undoUserMove() }
                                } else {
                                    null
                                },
                            ),
                        ),
                    )
                }

                when (val overlay = state.overlay) {
                    GameOverlay.None -> Unit
                    is GameOverlay.Promotion -> PromotionOverlay(
                        playerIsWhite = state.playerIsWhite,
                        onPick = viewModel::promote,
                        onCancel = viewModel::cancelPromotion,
                    )
                    GameOverlay.ResignConfirm -> ResignOverlay(
                        onConfirm = viewModel::confirmResign,
                        onCancel = viewModel::cancelResign,
                    )
                    is GameOverlay.GameOver -> LightFullscreenModal(
                        message = overlay.message,
                        onClose = {
                            viewModel.dismissGameOver()
                            goBack()
                        },
                    )
                }
            }
        }
    }
}

private fun gameTitle(state: GameUiState): String {
    if (!state.hasTimer) return state.botLabel
    val timeMs = if (state.playerIsWhite) state.whiteTimeMs else state.blackTimeMs
    return "${state.botLabel} - ${formatClock(timeMs)}"
}

@Composable
private fun PromotionOverlay(
    playerIsWhite: Boolean,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val color = if (playerIsWhite) WHITE else BLACK
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.CLOSE,
                onClick = onCancel,
            ),
            center = LightTopBarCenter.Text("Promote"),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            GameViewModel.PROMOTION_PIECES.forEach { (piece, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { onPick(piece) }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val colors = LightThemeTokens.colors
                    Box(
                        modifier = Modifier
                            .size(2.5f.gridUnitsAsDp())
                            .background(lerp(colors.background, colors.content, 0.50f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChessPieceImage(
                            piece = piece or color,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(0.15f.gridUnitsAsDp()),
                            contentDescription = label,
                        )
                    }
                    LightText(
                        text = label,
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(start = 0.75f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResignOverlay(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.CLOSE,
                onClick = onCancel,
            ),
            center = LightTopBarCenter.Text("Resign"),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = "Resign this game?",
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(
                    text = "CONFIRM",
                    onClick = onConfirm,
                ),
            ),
        )
    }
}

private fun formatClock(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
