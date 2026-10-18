package dev.neoneon.flamingo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

/**
 * REMOTE on the games list: create a game or join one by its invite code. Each option is offered
 * only while [features] keeps its path open. Whichever flow finishes hands its
 * [NewGameDestination] back through this screen to the games list, which opens the game — so
 * back from the game lands on the list, not here.
 */
class RemoteGameScreen(
    sealedActivity: SealedLightActivity,
    private val features: ServiceStatus.Features,
) : SimpleLightScreen<NewGameDestination>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

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
                    center = LightTopBarCenter.Text("Remote"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    // Choose a color, then share the minted code.
                    if (features.newGames) {
                        InfoRow(title = "New game") {
                            navigateTo(
                                screenFactory = { CreateGameScreen(it) },
                                resultCallback = { destination -> goBack(destination) },
                            )
                        }
                    }
                    // Enter the invite code a friend shared.
                    if (features.invites) {
                        InfoRow(title = "Join by code") {
                            navigateTo(
                                screenFactory = { JoinByPhraseScreen(it) },
                                resultCallback = { destination -> goBack(destination) },
                            )
                        }
                    }
                    LightText(
                        text = "Create for and Join games with friends on Light as well as " +
                            "other platforms.",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(
                            top = 1f.gridUnitsAsDp(),
                            end = 1f.gridUnitsAsDp(),
                        ),
                    )
                }
            }
        }
    }
}
