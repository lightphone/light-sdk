package dev.neoneon.flamingo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightColorsPreviewProvider
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The finished games put away in the archive, reached from Info: online games this seat archived
 * from their board, and local games saved from a finished Local board.
 *
 * The online half is read from the server, so it closes with the service. The local half never
 * touches the server, so it shows whatever the service status says.
 */
class ArchiveViewModel(dataStore: DataStore<Preferences>) : LightViewModel<Unit>() {
    private val localArchive: HotSeatArchiveStore = DataStoreHotSeatArchiveStore(dataStore)
    private val identityStore = PlayerIdentityStore(dataStore)
    // Built here rather than injected, like GamesListViewModel's: both are internal types, and a
    // public constructor may not name one.
    private val api = FlamingoApi()
    private val statusService = ServiceStatusService(dataStore)

    private val online = MutableStateFlow<OnlineArchive>(OnlineArchive.Loading)
    private var onlineLoad: Job? = null

    // The local half is followed rather than read once, so a game deleted on the replay screen is
    // gone by the time this screen is back on top.
    val state: StateFlow<ArchiveState> = combine(online, localArchive.games) { onlineGames, localGames ->
        ArchiveState(online = onlineGames, localGames = localGames)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ArchiveState())

    // Every time the screen comes back, not only the first: a game unarchived from its board has
    // left this list since.
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        loadOnline()
    }

    fun deleteLocalGame(id: String) {
        viewModelScope.launch { localArchive.remove(id) }
    }

    private fun loadOnline() {
        onlineLoad?.cancel()
        onlineLoad = viewModelScope.launch(Dispatchers.IO) {
            val status = statusService.current()
            if (status.level == ServiceStatus.Level.DISABLED) {
                online.value = OnlineArchive.Unavailable(status.message)
                return@launch
            }

            val playerId = identityStore.getOrCreate()
            api.listGames(playerId, archived = true).fold(
                onSuccess = { games ->
                    // Rows first, names after, as the games list does. Names already known are
                    // kept, so coming back to this screen doesn't flash the rows back to ids.
                    val known = (online.value as? OnlineArchive.Loaded)?.names.orEmpty()
                    online.value = OnlineArchive.Loaded(games, playerId, known)
                    val names = api.fetchPlayerNames(
                        games.flatMap { listOfNotNull(it.whitePlayerID, it.blackPlayerID) }
                    ).getOrNull()?.byPlayerId().orEmpty()
                    if (names.isNotEmpty()) {
                        online.value = OnlineArchive.Loaded(games, playerId, known + names)
                    }
                },
                onFailure = { online.value = OnlineArchive.Failed("Couldn't load online games") },
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}

class ArchiveScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ArchiveViewModel>(sealedActivity) {

    override val viewModelClass: Class<ArchiveViewModel>
        get() = ArchiveViewModel::class.java

    override fun createViewModel() = ArchiveViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        ArchiveContent(
            state = state,
            colors = themeColors,
            onBack = { goBack() },
            // GameView replays a finished game, and its bar's middle button takes it back out.
            onOpenOnlineGame = { game -> navigateTo(screenFactory = { GameView(it, game.id) }) },
            onOpenLocalGame = { game ->
                navigateTo(
                    screenFactory = { HotSeatReplayScreen(it, game) },
                    resultCallback = { id -> viewModel.deleteLocalGame(id) },
                )
            },
        )
    }
}

@Composable
private fun ArchiveContent(
    state: ArchiveState,
    colors: LightColors,
    onBack: () -> Unit = {},
    onOpenOnlineGame: (Game) -> Unit = {},
    onOpenLocalGame: (ArchivedHotSeatGame) -> Unit = {},
) {
    LightTheme(colors = colors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to info",
                ),
                center = LightTopBarCenter.Text("Archive"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            when {
                state.isLoading -> CenteredMessage("Loading…")

                state.isEmpty -> EmptyArchiveNote()

                else -> LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    SectionHeading("ONLINE GAMES")
                    OnlineGames(online = state.online, onOpen = onOpenOnlineGame)
                    SectionHeading("LOCAL GAMES", modifier = Modifier.padding(top = 1f.gridUnitsAsDp()))
                    LocalGames(games = state.localGames.orEmpty(), onOpen = onOpenLocalGame)
                }
            }
        }
    }
}

@Composable
private fun OnlineGames(online: OnlineArchive, onOpen: (Game) -> Unit) {
    when (online) {
        OnlineArchive.Loading -> SectionNote("Loading…")
        is OnlineArchive.Unavailable -> SectionNote(online.message.title)
        is OnlineArchive.Failed -> SectionNote(online.message)
        is OnlineArchive.Loaded -> if (online.games.isEmpty()) {
            SectionNote("No archived online games. Archive a finished game from its game actions.")
        } else {
            online.games.forEach { game ->
                // The same row as the games list, so a game reads the same in both.
                GameListRow(
                    game = game,
                    playerId = online.playerId,
                    names = online.names,
                    modifier = Modifier
                        .clickable { onOpen(game) }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )
            }
        }
    }
}

@Composable
private fun LocalGames(games: List<ArchivedHotSeatGame>, onOpen: (ArchivedHotSeatGame) -> Unit) {
    if (games.isEmpty()) {
        SectionNote("No saved local games. When a local game ends, save it here from its game actions.")
    } else {
        games.forEach { game ->
            ArchivedLocalGameRow(
                game = game,
                modifier = Modifier
                    .clickable { onOpen(game) }
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = modifier,
    )
}

// Stands in for a group's rows. Lightened, so it doesn't read as a row that could be opened.
@Composable
private fun SectionNote(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 1f.gridUnitsAsDp())
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    )
}

/** Titled with the result: both colors sat at the same phone, so there is no opponent to name. */
@Composable
private fun ArchivedLocalGameRow(game: ArchivedHotSeatGame, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        LightText(
            text = game.outcome.label,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = "saved ${game.archivedAgo()}",
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}

@Composable
private fun ColumnScope.EmptyArchiveNote() {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(
            text = "No archived games",
            variant = LightTextVariant.Heading,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        LightText(
            text = "Archive a finished game, online or local, from its game actions.",
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.5f.gridUnitsAsDp()),
        )
    }
}

// MARK: Previews

private const val PREVIEW_DAY_MILLIS = 24 * 60 * 60 * 1000L

private fun previewLocalGame(id: String, outcome: ArchivedOutcome, daysAgo: Int) = ArchivedHotSeatGame(
    game = HotSeatGame(id = id, moves = listOf("f2f3", "e7e5", "g2g4", "d8h4")),
    outcome = outcome,
    archivedAtMillis = System.currentTimeMillis() - daysAgo * PREVIEW_DAY_MILLIS,
)

private val previewLocalGames = listOf(
    previewLocalGame("a", ArchivedOutcome.BlackWins, daysAgo = 0),
    previewLocalGame("b", ArchivedOutcome.Draw, daysAgo = 2),
    previewLocalGame("c", ArchivedOutcome.WhiteWins, daysAgo = 9),
)

private fun previewOnlineGame(id: String, status: String, opponent: String) = Game(
    id = id,
    fen = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3",
    whitePlayerID = "us",
    blackPlayerID = opponent,
    status = status,
    createdAt = "2026-10-01T12:00:00Z",
    updatedAt = "2026-10-03T12:00:00Z",
    archivedByWhite = true,
)

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewArchiveBoth(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    ArchiveContent(
        state = ArchiveState(
            online = OnlineArchive.Loaded(
                games = listOf(
                    previewOnlineGame("1", status = "checkmate", opponent = "them"),
                    previewOnlineGame("2", status = "resigned", opponent = "someone"),
                ),
                playerId = "us",
                names = mapOf("them" to "orange489", "someone" to "blueberry764"),
            ),
            localGames = previewLocalGames,
        ),
        colors = colors,
    )
}

// The server hasn't answered yet, but the local games are worth showing already.
@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewArchiveOnlineLoading(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    ArchiveContent(state = ArchiveState(localGames = previewLocalGames), colors = colors)
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewArchiveOnlineOnly(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    ArchiveContent(
        state = ArchiveState(
            online = OnlineArchive.Loaded(
                games = listOf(previewOnlineGame("1", status = "draw", opponent = "them")),
                playerId = "us",
                names = mapOf("them" to "orange489"),
            ),
            localGames = emptyList(),
        ),
        colors = colors,
    )
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewArchiveEmpty(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    ArchiveContent(
        state = ArchiveState(online = OnlineArchive.Loaded(emptyList(), playerId = "us"), localGames = emptyList()),
        colors = colors,
    )
}
