// "This file belongs in my Sleep Training App."
package com.thelightphone.sleeptrainer

// ----- IMPORTS ----- // [Brings in resources for the app.]
    // COMPOSE [Builds the screen] //
    import androidx.compose.foundation.background
    import androidx.compose.foundation.layout.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.tooling.preview.Preview

    // LIGHT SDK [Functions on Light Phone] //
    import com.thelightphone.sdk.InitialScreen
    import com.thelightphone.sdk.SealedLightActivity
    import com.thelightphone.sdk.SimpleLightScreen
    import com.thelightphone.sdk.ui.*

    // COROUTINE [Allows events to occur over time] //
    import kotlinx.coroutines.delay
    import kotlinx.coroutines.launch

// ----- SCREEN ----- // [This creates the actual screen the User will see.]
@InitialScreen
class SleepTrainerHomeScreen(sealedActivity: SealedLightActivity) :
    SimpleLightScreen<Unit>(sealedActivity) {

    // CONTENT FUNCTION ["Draws" everything the User sees] //
    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()

        // ----- APP MEMORY ----- //
        var currentIntervalIndex by remember { mutableStateOf(0) }
            // "Which Ferber interval am I currently on?" //
        var timeLeftSeconds by remember { mutableStateOf(FerberSchedule.getIntervalMinutes(0) * 60) }
            // "How many seconds are left in THIS interval?" //
        var isRunning by remember { mutableStateOf(false) }
            // "Is the timer currently running?" //
        var isWaitingForNext by remember { mutableStateOf(false) }
            // "Has an interval ended, and are we waiting for the user to start the next one?" //
        var sessionTotalSeconds by remember { mutableStateOf(0) }
            // "How long has the TOTAL session been (all intervals combined)?" //
        var intervalsCompleted by remember { mutableStateOf(0) }
            // "How many intervals has the user finished?" //

        var history by remember { mutableStateOf<List<SessionData>>(emptyList()) }

        // ----- LOAD HISTORY ----- //
        LaunchedEffect(Unit) {
            history = HistoryRepository.loadHistory(lightContext.dataStore)
        }

        // ----- TIMER LOGIC ----- //
        LaunchedEffect(isRunning, timeLeftSeconds) {
            if (isRunning && timeLeftSeconds > 0) {
                delay(1000)
                timeLeftSeconds--
                sessionTotalSeconds++
            }

            if (isRunning && timeLeftSeconds == 0) {
                isRunning = false
                isWaitingForNext = true
                intervalsCompleted++
                
                // ----- VIBRATION ----- //
                lightContext.vibrate(500)
            }
        }

        SleepTrainerHomeContent(
            currentIntervalIndex = currentIntervalIndex,
            timeLeftSeconds = timeLeftSeconds,
            isRunning = isRunning,
            isWaitingForNext = isWaitingForNext,
            history = history,
            onToggleTimer = {
                if (isRunning || isWaitingForNext) {
                    // This is actually the "STOP" button logic
                    val total = sessionTotalSeconds
                    val completed = intervalsCompleted
                    scope.launch {
                        HistoryRepository.saveSession(lightContext.dataStore, total, completed)
                        
                        // Reset all local session states
                        isRunning = false
                        isWaitingForNext = false
                        currentIntervalIndex = 0
                        timeLeftSeconds = FerberSchedule.getIntervalMinutes(0) * 60
                        sessionTotalSeconds = 0
                        intervalsCompleted = 0
                        
                        // Show the summary screen
                        navigateTo({ SleepTrainerSummaryScreen(it, total, completed) })
                    }
                } else {
                    // START
                    isRunning = true
                }
            },
            onNextInterval = {
                currentIntervalIndex++
                timeLeftSeconds = FerberSchedule.getIntervalMinutes(currentIntervalIndex) * 60
                isWaitingForNext = false
                isRunning = true
            },
            onViewHistory = {
                navigateTo(::SleepTrainerHistoryScreen)
            }
        )
    }
}

@Composable
fun SleepTrainerHomeContent(
    currentIntervalIndex: Int,
    timeLeftSeconds: Int,
    isRunning: Boolean,
    isWaitingForNext: Boolean,
    history: List<SessionData>,
    onToggleTimer: () -> Unit,
    onNextInterval: () -> Unit,
    onViewHistory: () -> Unit
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
                center = LightTopBarCenter.Text("Cozy Sleep"),
                modifier = Modifier.padding(bottom = 2f.gridUnitsAsDp())
            )

            LightText(
                text = "INTERVAL ${currentIntervalIndex + 1}",
                variant = LightTextVariant.Detail
            )

            LightText(
                text = if (isWaitingForNext) "00:00" else FerberSchedule.formatTime(timeLeftSeconds),
                variant = LightTextVariant.Heading,
                modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp())
            )

            // MAIN BUTTON (START / STOP)
            LightText(
                text = if (isRunning || isWaitingForNext) "STOP" else "START",
                variant = LightTextVariant.Subtitle,
                modifier = Modifier
                    .lightClickable { onToggleTimer() }
                    .padding(1f.gridUnitsAsDp()),
                maxLines = 1
            )

            // NEXT INTERVAL BUTTON (only shows when one ends)
            if (isWaitingForNext) {
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "NEXT INTERVAL",
                    variant = LightTextVariant.Subtitle,
                    modifier = Modifier
                        .lightClickable { onNextInterval() }
                        .padding(1f.gridUnitsAsDp()),
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (history.isNotEmpty()) {
                LightText(
                    text = "HISTORY",
                    variant = LightTextVariant.Detail,
                    modifier = Modifier
                        .lightClickable { onViewHistory() }
                        .padding(bottom = 0.5f.gridUnitsAsDp())
                )

                history.take(3).forEach { session ->
                    LightText(
                        text = "${session.totalTimeSeconds / 60}m | ${session.date}",
                        variant = LightTextVariant.Detail
                    )
                }
            }

            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun SleepTrainerPreviewStart() {
    SleepTrainerHomeContent(
        currentIntervalIndex = 0,
        timeLeftSeconds = 120,
        isRunning = false,
        isWaitingForNext = false,
        history = emptyList(),
        onToggleTimer = {},
        onNextInterval = {},
        onViewHistory = {}
    )
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun SleepTrainerPreviewRunning() {
    SleepTrainerHomeContent(
        currentIntervalIndex = 1,
        timeLeftSeconds = 45,
        isRunning = true,
        isWaitingForNext = false,
        history = listOf(
            SessionData(300, "Jul 29", 2),
            SessionData(600, "Jul 29", 4)
        ),
        onToggleTimer = {},
        onNextInterval = {},
        onViewHistory = {}
    )
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun SleepTrainerPreviewWaiting() {
    SleepTrainerHomeContent(
        currentIntervalIndex = 0,
        timeLeftSeconds = 0,
        isRunning = false,
        isWaitingForNext = true,
        history = emptyList(),
        onToggleTimer = {},
        onNextInterval = {},
        onViewHistory = {}
    )
}
