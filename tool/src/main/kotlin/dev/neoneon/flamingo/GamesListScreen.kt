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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollBarPosition
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

class GamesListViewModel(
    private val identityStore: PlayerIdentityStore,
    dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {
    private val api = FlamingoApi()
    // Built here rather than injected, like `api` above: both are internal types, and a public
    // constructor may not name one.
    private val statusService = ServiceStatusService(dataStore)

    sealed class State {
        data object Loading : State()
        // Carries the local player id so each row can say whose turn it is, and the display
        // names for every seat on screen so each row can title itself "white vs black".
        data class Loaded(
            val games: List<Game>,
            val playerId: String,
            val names: Map<String, String> = emptyMap(),
        ) : State()
        data class Error(val message: String) : State()
        /**
         * The service is `disabled`: the message is the screen, and there is nothing to list.
         * [reason] says why — a planned [ServiceStatus.Reason.MAINTENANCE] offers "Check again".
         */
        data class Unavailable(
            val message: ServiceStatus.Message,
            val reason: ServiceStatus.Reason? = null,
        ) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    /** What the service is letting us do. Unrestricted until a document says otherwise. */
    private val _status = MutableStateFlow(ServiceStatus.UNRESTRICTED)
    val status: StateFlow<ServiceStatus> = _status

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        loadGames()
    }

    /**
     * "Check again" on the maintenance screen: reads the document whatever the cache says, then
     * loads as usual — so lifting the pause shows the games straight away.
     */
    fun checkAgain() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = State.Loading
            statusService.refresh()
            loadGames()
        }
    }

    private fun showStatus(status: ServiceStatus): Boolean {
        _status.value = status
        if (status.level != ServiceStatus.Level.DISABLED) return false
        _state.value = State.Unavailable(status.message, status.reason)
        return true
    }

    private fun loadGames() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = State.Loading

            // The status first: it decides what the rest of the screen may offer, and it is a
            // static file on a CDN, so it lands long before the games list would.
            if (showStatus(statusService.current())) return@launch

            val playerId = identityStore.getOrCreate()
            // Once per install, and never blocking the list: a game list without names still
            // works, so a failure here just leaves the rows titled by id until the next launch.
            identityStore.ensureRegistered(api, playerId)
            api.listGames(playerId).fold(
                onSuccess = { games ->
                    // Show the games first, then fill the names in. One extra request for the
                    // whole list, and the list is never held back waiting for it.
                    _state.value = State.Loaded(games, playerId)
                    val names = api.fetchPlayerNames(
                        games.flatMap { listOfNotNull(it.whitePlayerID, it.blackPlayerID) }
                    ).getOrNull()?.byPlayerId().orEmpty()
                    if (names.isNotEmpty()) {
                        _state.value = State.Loaded(games, playerId, names)
                    }
                },
                onFailure = { error ->
                    // A rejection may mean the cord was pulled after this screen read the
                    // document — a deploy, say. Re-read it, so the player sees the wording
                    // written for that rather than a bare HTTP error.
                    if (error is HttpStatusException &&
                        error.status in ServiceStatus.SERVER_REJECTION_CODES
                    ) {
                        val refreshed = statusService.refreshAfterServerRejection()
                        if (refreshed != null && showStatus(refreshed)) return@fold
                    }
                    _state.value = State.Error(error.message ?: "Unable to load games")
                },
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

    override fun createViewModel() = GamesListViewModel(
        PlayerIdentityStore(lightContext.dataStore),
        lightContext.dataStore,
    )

    // Pops this list and opens [destination] in GameView, so back from the game returns
    // here rather than to the finished create/join screen.
    private fun openGame(destination: NewGameDestination) {
        navigateTo(screenFactory = {
            GameView(it, destination.gameId, initialColor = destination.color)
        })
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val status by viewModel.status.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Games"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                // `disabled` says it on the whole screen below; `ok` has nothing to say. This
                // line is for the levels in between, which leave games that still play.
                if (status.level == ServiceStatus.Level.DEGRADED ||
                    status.level == ServiceStatus.Level.READ_ONLY
                ) {
                    ServiceStatusNotice(status.message)
                }

                when (val current = state) {
                    is GamesListViewModel.State.Loading -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            LightText(
                                text = "Loading…",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                            )
                        }
                    }

                    is GamesListViewModel.State.Unavailable -> {
                        LightScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 1f.gridUnitsAsDp()),
                            // Outside reserves a gutter whether or not the bar is actually
                            // shown, which would skew this screen's centered text off-center
                            // in the common case where the message fits with no scrolling.
                            scrollBarPosition = LightScrollBarPosition.Inside,
                        ) {
                            LightText(
                                text = current.message.title,
                                variant = LightTextVariant.Heading,
                                align = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 1f.gridUnitsAsDp()),
                            )
                            LightText(
                                text = current.message.body,
                                variant = LightTextVariant.Copy,
                                align = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 0.5f.gridUnitsAsDp()),
                            )
                            // A planned pause ends on its own, so offer the way back in. An
                            // emergency has no such promise, and offers nothing.
                            if (current.reason == ServiceStatus.Reason.MAINTENANCE) {
                                LightText(
                                    text = "CHECK AGAIN",
                                    variant = LightTextVariant.Copy,
                                    align = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 1f.gridUnitsAsDp())
                                        .clickable { viewModel.checkAgain() }
                                        .padding(vertical = 0.5f.gridUnitsAsDp()),
                                )
                            }
                        }
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
                            EmptyGamesNote(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
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
                                        playerId = current.playerId,
                                        names = current.names,
                                        modifier = Modifier
                                            // Finished games open in the same screen as active
                                            // ones — GameView replays the move log, so it's the
                                            // only place that can tell a checkmate from an
                                            // ordinary position, and it presents the ending.
                                            .clickable {
                                                navigateTo(screenFactory = { GameView(it, game.id) })
                                            }
                                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                                    )
                                }
                            }
                        }
                    }
                }

                LightBottomBar(
                    // JOIN and the add button drop out when the service has closed those paths:
                    // the server would refuse the request anyway, and a button that can only
                    // produce an error is worse than no button. Info always stays — the terms
                    // and the privacy policy are readable whatever else is going on.
                    items = listOfNotNull(
                        // Info: read-only Terms of Service and Privacy Policy.
                        LightBarButton.LightIcon(
                            icon = LightIcons.ELLIPSES,
                            onClick = { navigateTo(screenFactory = { InfoScreen(it) }) },
                            contentDescription = "Info",
                        ),
                        // Join a game a friend created by entering the invite code they shared.
                        LightBarButton.Text(
                            text = "JOIN",
                            onClick = {
                                navigateTo(
                                    screenFactory = { JoinByPhraseScreen(it) },
                                    resultCallback = { destination -> openGame(destination) },
                                )
                            },
                        ).takeIf { status.features.invites },
                        // Create a game: choose a color, then share the minted code.
                        LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            onClick = {
                                navigateTo(
                                    screenFactory = { CreateGameScreen(it) },
                                    resultCallback = { destination -> openGame(destination) },
                                )
                            },
                            contentDescription = "New game",
                        ).takeIf { status.features.newGames },
                    ),
                )
            }
        }
    }
}

// Shown only once the list has loaded and come back empty. It names no buttons: JOIN and
// the add button drop out when the service closes those paths, and this text stays the same.
private val emptyGamesParagraphs = listOf(
    "Chess was invented for two people sitting at one table. Somewhere near you is someone " +
        "who'd love to beat you at it, or lose to you gracefully. Go find them.",
    "Set up a real board. Shake a real hand. Watch your opponent's face the moment they spot " +
        "your knight fork. No screen can deliver that.",
    "Different cities? Different time zones? Stuck on a very long train ride? That's what " +
        "this Chess tool is for: a way to keep playing when you can't share a table.",
    "When you can meet, though, meet. The pieces are lighter in your hand anyway.",
)

@Composable
private fun EmptyGamesNote(modifier: Modifier = Modifier) {
    LightScrollView(
        modifier = modifier.padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = "Your move — in person.",
            variant = LightTextVariant.Heading,
            modifier = Modifier.padding(
                top = 1f.gridUnitsAsDp(),
                bottom = 1f.gridUnitsAsDp(),
            ),
        )
        emptyGamesParagraphs.forEach { paragraph ->
            LightText(
                text = paragraph,
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}
