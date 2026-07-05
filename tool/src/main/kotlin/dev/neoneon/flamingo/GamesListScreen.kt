package dev.neoneon.flamingo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class GamesListViewModel(private val identityStore: PlayerIdentityStore) : LightViewModel<Unit>() {
    private val api = FlamingoApi()

    sealed class State {
        data object Loading : State()
        data class Loaded(val games: List<Game>) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        loadGames()
    }

    private fun loadGames() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = State.Loading
            val identity = identityStore.getOrCreate()
            api.listGames(identity.whitePlayerId).fold(
                onSuccess = { games -> _state.value = State.Loaded(games) },
                onFailure = { error -> _state.value = State.Error(error.message ?: "Unable to load games") },
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}

@InitialScreen
class GamesListScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, GamesListViewModel>(sealedActivity) {

    override val viewModelClass: Class<GamesListViewModel>
        get() = GamesListViewModel::class.java

    override fun createViewModel() = GamesListViewModel(PlayerIdentityStore(lightContext.dataStore))

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
                    center = LightTopBarCenter.Text("Games"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.ADD,
                        onClick = {
                            val gameId = UUID.randomUUID().toString()
                            navigateTo(screenFactory = { GameView(it, gameId) })
                        },
                        contentDescription = "New game",
                    ),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when (val current = state) {
                    is GamesListViewModel.State.Loading -> {
                        LightText(
                            text = "Loading…",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                        )
                    }

                    is GamesListViewModel.State.Error -> {
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

                    is GamesListViewModel.State.Loaded -> {
                        if (current.games.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                LightText(
                                    text = "No games yet.",
                                    variant = LightTextVariant.Copy,
                                    align = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                                )
                            }
                        } else {
                            LightScrollView(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(start = 1f.gridUnitsAsDp()),
                            ) {
                                current.games.forEach { game ->
                                    GameListRow(
                                        game = game,
                                        modifier = Modifier
                                            .clickable {
                                                if (game.isActive) {
                                                    navigateTo(screenFactory = { GameView(it, game.id) })
                                                } else {
                                                    navigateTo(screenFactory = { GameDetailScreen(it, game.id) })
                                                }
                                            }
                                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
