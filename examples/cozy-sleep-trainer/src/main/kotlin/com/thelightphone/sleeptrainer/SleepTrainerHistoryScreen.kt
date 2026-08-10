package com.thelightphone.sleeptrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.*

class SleepTrainerHistoryScreen(sealedActivity: SealedLightActivity) :
    SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var history by remember { mutableStateOf<List<SessionData>>(emptyList()) }

        LaunchedEffect(Unit) {
            history = HistoryRepository.loadHistory(lightContext.dataStore)
        }

        SleepTrainerHistoryContent(
            history = history,
            onBack = { goBack() }
        )
    }
}

@Composable
fun SleepTrainerHistoryContent(
    history: List<SessionData>,
    onBack: () -> Unit
) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background)
        ) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { onBack() }
                ),
                center = LightTopBarCenter.Text("History")
            )

            if (history.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LightText(
                        text = "No sessions yet",
                        variant = LightTextVariant.Detail
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(1f.gridUnitsAsDp())
                ) {
                    items(history) { session ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 0.5f.gridUnitsAsDp())
                        ) {
                            LightText(
                                text = session.date,
                                variant = LightTextVariant.Detail,
                                lighten = true
                            )
                            LightText(
                                text = "${session.totalTimeSeconds / 60} min • ${session.intervalsCompleted} intervals",
                                variant = LightTextVariant.Copy
                            )
                            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                        }
                    }
                }
            }
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun SleepTrainerHistoryPreview() {
    SleepTrainerHistoryContent(
        history = listOf(
            SessionData(300, "Jul 29", 3),
            SessionData(600, "Jul 29", 5),
            SessionData(120, "Jul 28", 1)
        ),
        onBack = {}
    )
}
