package dev.neoneon.flamingo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The finished games put away in the archive, reached from Info. Today that is the local games
 * saved from a finished Local board; they never reach the server, so this screen works whatever
 * the service status says.
 */
class ArchiveViewModel(dataStore: DataStore<Preferences>) : LightViewModel<Unit>() {
    private val localArchive: HotSeatArchiveStore = DataStoreHotSeatArchiveStore(dataStore)

    data class State(
        /** Newest first; `null` until the archive has been read. */
        val localGames: List<ArchivedHotSeatGame>? = null,
    )

    // Followed rather than read once, so a game deleted on the replay screen is gone by the time
    // this screen is back on top.
    val state: StateFlow<State> = localArchive.games
        .map { State(localGames = it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, State())

    fun deleteLocalGame(id: String) {
        viewModelScope.launch { localArchive.remove(id) }
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
    state: ArchiveViewModel.State,
    colors: LightColors,
    onBack: () -> Unit = {},
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

            val localGames = state.localGames
            when {
                localGames == null -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(text = "Loading…", variant = LightTextVariant.Copy)
                }

                localGames.isEmpty() -> EmptyArchiveNote()

                else -> LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    // Headed although it is the only group so far: online games archived on the
                    // server join it under a heading of their own.
                    LightText(
                        text = "LOCAL GAMES",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                    localGames.forEach { game ->
                        ArchivedLocalGameRow(
                            game = game,
                            modifier = Modifier
                                .clickable { onOpenLocalGame(game) }
                                .padding(vertical = 0.75f.gridUnitsAsDp()),
                        )
                    }
                }
            }
        }
    }
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
            text = "When a local game ends, save it here from its game actions.",
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

private fun previewGame(id: String, outcome: ArchivedOutcome, daysAgo: Int) = ArchivedHotSeatGame(
    game = HotSeatGame(id = id, moves = listOf("f2f3", "e7e5", "g2g4", "d8h4")),
    outcome = outcome,
    archivedAtMillis = System.currentTimeMillis() - daysAgo * PREVIEW_DAY_MILLIS,
)

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewArchiveLocalGames(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    ArchiveContent(
        state = ArchiveViewModel.State(
            localGames = listOf(
                previewGame("a", ArchivedOutcome.BlackWins, daysAgo = 0),
                previewGame("b", ArchivedOutcome.Draw, daysAgo = 2),
                previewGame("c", ArchivedOutcome.WhiteWins, daysAgo = 9),
            ),
        ),
        colors = colors,
    )
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewArchiveEmpty(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    ArchiveContent(state = ArchiveViewModel.State(localGames = emptyList()), colors = colors)
}
