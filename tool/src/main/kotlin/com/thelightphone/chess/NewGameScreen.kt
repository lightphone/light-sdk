package com.thelightphone.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.chess.engine.BotLevel
import com.thelightphone.chess.engine.GameTimer
import com.thelightphone.chess.engine.StartColor
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

class NewGameScreen(sealedActivity: SealedLightActivity) :
    SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var timer by rememberSaveable { mutableStateOf(GameTimer.NONE.name) }
        var color by rememberSaveable { mutableStateOf(StartColor.WHITE.name) }
        var bot by rememberSaveable { mutableStateOf(BotLevel.MEDIUM.name) }

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
                    ),
                    center = LightTopBarCenter.Text("New game"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    SectionLabel("Timer")
                    GameTimer.entries.forEach { option ->
                        SelectableRow(
                            label = option.label,
                            selected = timer == option.name,
                            onClick = { timer = option.name },
                        )
                    }

                    SectionLabel("Your color")
                    StartColor.entries.forEach { option ->
                        SelectableRow(
                            label = option.label,
                            selected = color == option.name,
                            onClick = { color = option.name },
                        )
                    }

                    SectionLabel("Bot")
                    BotLevel.entries.forEach { option ->
                        SelectableRow(
                            label = option.label,
                            selected = bot == option.name,
                            onClick = { bot = option.name },
                        )
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "START",
                            onClick = {
                                navigateTo(
                                    screenFactory = {
                                        GameScreen(
                                            it,
                                            GameLaunch.New(
                                                timer = GameTimer.valueOf(timer),
                                                color = StartColor.valueOf(color),
                                                bot = BotLevel.valueOf(bot),
                                            ),
                                        )
                                    },
                                )
                            },
                        ),
                    ),
                )
            }
        }
    }
}
