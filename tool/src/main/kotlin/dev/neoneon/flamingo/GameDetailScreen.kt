package dev.neoneon.flamingo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GameDetailViewModel(private val gameId: String) : LightViewModel<Unit>() {
    private val api = FlamingoApi()

    sealed class State {
        data object Loading : State()
        data class Loaded(
            val moves: List<Move>,
            val fallbackFen: String,
            val currentIndex: Int,
        ) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (_state.value is State.Loading) loadGame()
    }

    private fun loadGame() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = State.Loading
            api.fetchGame(gameId).fold(
                onSuccess = { detail ->
                    _state.value = State.Loaded(
                        moves = detail.moves,
                        fallbackFen = detail.game.fen,
                        currentIndex = (detail.moves.size - 1).coerceAtLeast(0),
                    )
                },
                onFailure = { error ->
                    _state.value = State.Error(error.message ?: "Unable to load game")
                },
            )
        }
    }

    fun stepBack() {
        _state.update { current ->
            val loaded = current as? State.Loaded ?: return@update current
            if (loaded.currentIndex <= 0) return@update current
            loaded.copy(currentIndex = loaded.currentIndex - 1)
        }
    }

    fun stepForward() {
        _state.update { current ->
            val loaded = current as? State.Loaded ?: return@update current
            if (loaded.currentIndex >= loaded.moves.size - 1) return@update current
            loaded.copy(currentIndex = loaded.currentIndex + 1)
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}

class GameDetailScreen(
    sealedActivity: SealedLightActivity,
    private val gameId: String,
) : LightScreen<Unit, GameDetailViewModel>(sealedActivity) {

    override val viewModelClass: Class<GameDetailViewModel>
        get() = GameDetailViewModel::class.java

    override fun createViewModel() = GameDetailViewModel(gameId)

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
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when (val current = state) {
                    is GameDetailViewModel.State.Loading -> {
                        LightText(
                            text = "Loading…",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                        )
                    }

                    is GameDetailViewModel.State.Error -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            LightText(
                                text = current.message,
                                variant = LightTextVariant.Copy,
                                align = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                            )
                        }
                    }

                    is GameDetailViewModel.State.Loaded -> {
                        val fen = current.moves.getOrNull(current.currentIndex)?.fenAfter
                            ?: current.fallbackFen
                        val moveLabel = if (current.moves.isEmpty()) {
                            "No moves yet"
                        } else {
                            "Move ${current.currentIndex + 1} of ${current.moves.size}"
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = moveLabel,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                            )
                            ChessBoard(fen = fen)
                        }

                        LightBottomBar(
                            items = listOf(
                                LightBarButton.LightIcon(
                                    icon = LightIcons.REWIND,
                                    onClick = { viewModel.stepBack() },
                                    contentDescription = "Previous move",
                                ),
                                LightBarButton.LightIcon(
                                    icon = LightIcons.FAST_FORWARD,
                                    onClick = { viewModel.stepForward() },
                                    contentDescription = "Next move",
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChessBoard(fen: String) {
    val ranks = parseFenBoard(fen)
    Column {
        ranks.forEach { rank ->
            Row {
                rank.forEach { square ->
                    LightText(
                        text = " $square ",
                        variant = LightTextVariant.Copy,
                        monospace = true,
                    )
                }
            }
        }
    }
}
