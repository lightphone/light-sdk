package com.thelightphone.sleeptrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.*

class SleepTrainerSummaryScreen(
    sealedActivity: SealedLightActivity,
    private val totalSeconds: Int,
    private val intervalsCompleted: Int
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        SleepTrainerSummaryContent(
            totalSeconds = totalSeconds,
            intervalsCompleted = intervalsCompleted,
            onDone = { goBack() }
        )
    }
}

@Composable
fun SleepTrainerSummaryContent(
    totalSeconds: Int,
    intervalsCompleted: Int,
    onDone: () -> Unit
) {
    val themeColors by LightThemeController.colors.collectAsState()

    LightTheme(colors = themeColors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LightTopBar(
                center = LightTopBarCenter.Text("Session Summary"),
                modifier = Modifier.padding(bottom = 2f.gridUnitsAsDp())
            )

            Spacer(modifier = Modifier.weight(1f))

            LightText(
                text = "TOTAL TIME",
                variant = LightTextVariant.Detail
            )
            LightText(
                text = FerberSchedule.formatTime(totalSeconds),
                variant = LightTextVariant.Heading,
                modifier = Modifier.padding(bottom = 2f.gridUnitsAsDp())
            )

            LightText(
                text = "INTERVALS COMPLETED",
                variant = LightTextVariant.Detail
            )
            LightText(
                text = "$intervalsCompleted",
                variant = LightTextVariant.Heading
            )

            Spacer(modifier = Modifier.weight(1f))

            LightText(
                text = "DONE",
                variant = LightTextVariant.Subtitle,
                modifier = Modifier
                    .lightClickable { onDone() }
                    .padding(1f.gridUnitsAsDp())
            )

            Spacer(modifier = Modifier.height(2f.gridUnitsAsDp()))
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun SleepTrainerSummaryPreview() {
    SleepTrainerSummaryContent(
        totalSeconds = 450,
        intervalsCompleted = 3,
        onDone = {}
    )
}
