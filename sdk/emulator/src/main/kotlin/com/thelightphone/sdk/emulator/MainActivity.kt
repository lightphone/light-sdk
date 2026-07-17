package com.thelightphone.sdk.emulator

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.server.LightSdkServer.queryEnabledClients
import com.thelightphone.sdk.server.LightSdkServer.runningAsSystemApp
import com.thelightphone.sdk.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    enum class Nav {
        LockScreen, Toolbox, Settings
    }

    private val currentNavFlow = MutableStateFlow(Nav.LockScreen)

    private val nowPlayingReader by lazy { NowPlayingReader(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (!runningAsSystemApp) {
            Log.w(
                "LightEmulator",
                "WARNING: LightOS emulator is NOT running as a system app and may not work."
            )
        }
        val serverSettings = LightSdkServer.provideSdkSettings(this)
        nowPlayingReader.start()
        setContent {
            val themeColors by LightThemeController.colors.collectAsState()
            val currentNav by currentNavFlow.collectAsState()
            LightTheme(colors = themeColors) {
                when (currentNav) {
                    Nav.LockScreen -> {
                        val nowPlaying by nowPlayingReader.state.collectAsState()
                        LightLockscreen(
                            nowPlaying = nowPlaying,
                            onPlayPause = nowPlayingReader::playPause,
                            onNext = nowPlayingReader::next,
                            onPrevious = nowPlayingReader::previous,
                            onUnlockClicked = { currentNavFlow.value = Nav.Toolbox },
                        )
                    }
                    Nav.Toolbox -> {
                        ToolList(
                            fetchExternalTools = {
                                queryEnabledClients(serverSettings).map {
                                    val appInfo = it.packageInfo.applicationInfo!!
                                    val label =
                                        packageManager.getApplicationLabel(appInfo).toString()
                                    ExternalTool(label, it.packageInfo.packageName)
                                }
                            }, launchPackage = {
                                packageManager.getLaunchIntentForPackage(it)?.let { intent ->
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                                    val options =
                                        android.app.ActivityOptions.makeCustomAnimation(this, 0, 0)
                                    startActivity(intent, options.toBundle())
                                }
                            }, launchDefaultTool = {
                                when (it) {
                                    DefaultTool.Settings -> currentNavFlow.value = Nav.Settings
                                }
                            })
                    }

                    Nav.Settings -> {
                        EmulatorSettings(serverSettings) {
                            currentNavFlow.value = Nav.Toolbox
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val screenTurnedOff = intent.extras?.getBoolean(LightSdkServer.SCREEN_OFF_FLAG, false) == true
        if (screenTurnedOff) {
            currentNavFlow.value = Nav.LockScreen
        }
    }

    override fun onDestroy() {
        nowPlayingReader.stop()
        super.onDestroy()
    }
}

private sealed class Tool(val label: String)
private class ExternalTool(label: String, val packageName: String) : Tool(label)
private sealed class DefaultTool(label: String) : Tool(label) {
    object Settings : DefaultTool("Settings")
}


private val defaultTools: List<Tool> = listOf(
    DefaultTool.Settings
)

@Composable
private fun ToolList(
    fetchExternalTools: suspend () -> List<Tool>,
    launchPackage: (String) -> Unit,
    launchDefaultTool: (DefaultTool) -> Unit
) {
    // TODO page indicator
    val toolPageSize = 6
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var pages by remember { mutableStateOf(defaultTools.chunked(toolPageSize)) }
    val currentPage by remember {
        derivedStateOf {
            pages.getOrNull(currentPageIndex) ?: pages.first()
        }
    }
    LaunchedEffect(Unit) {
        val externalTools = fetchExternalTools()
        pages = (defaultTools + externalTools).chunked(toolPageSize)
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState {},
                onDragStopped = { velocity ->
                    if (velocity < -200f && currentPageIndex < pages.size - 1) {
                        currentPageIndex++
                    } else if (velocity > 200f && currentPageIndex > 0) {
                        currentPageIndex--
                    }
                }
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            for (tool in currentPage) {
                LightText(
                    text = tool.label,
                    variant = LightTextVariant.Subtitle,
                    modifier = Modifier.clickable {
                        when (tool) {
                            is DefaultTool -> launchDefaultTool(tool)
                            is ExternalTool -> launchPackage(tool.packageName)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LightLockscreen(
    nowPlaying: LightNowPlayingState? = null,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onUnlockClicked: () -> Unit,
) {
    Surface {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(1f.gridUnitsAsDp())
        ) {
            Row(Modifier.fillMaxWidth()) {
                LightIcon(icon = LightIcons.WIFI, size = 1f)
                Spacer(Modifier.width(0.5f.gridUnitsAsDp()))
                LightIcon(icon = LightIcons.BLUETOOTH, size = 1f)
                Spacer(Modifier.weight(1f))
                LightIcon(icon = LightIcons.BATTERY_CHARGING, size = 1f)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                var time by remember { mutableStateOf(LocalTime.now()) }

                LaunchedEffect(Unit) {
                    while (true) {
                        time = LocalTime.now()
                        delay(1000)
                    }
                }

                val formatter = remember { DateTimeFormatter.ofPattern("h:mm") }
                LightText(
                    time.format(formatter),
                    variant = LightTextVariant.Title,
                    modifier = Modifier.align(BiasAlignment(0f, -0.1f))
                )
            }
            if (nowPlaying != null) {
                NowPlayingControls(
                    nowPlaying = nowPlaying,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                )
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
            }
            Box(Modifier.clickable(onClick = onUnlockClicked)) {
                LightIcon(
                    icon = LightIcons.CIRCLE,
                    size = 2f
                )
            }
        }
    }
}

/** Lock-screen media widget: track title/artist + transport controls, driven by
 *  whatever tool currently owns an active MediaSession. */
@Composable
private fun NowPlayingControls(
    nowPlaying: LightNowPlayingState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LightText(nowPlaying.title, variant = LightTextVariant.Subheading)
        nowPlaying.artist?.let {
            LightText(it, variant = LightTextVariant.Detail)
        }
        Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                1.5f.gridUnitsAsDp(),
                Alignment.CenterHorizontally,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (nowPlaying.hasPrevious) {
                LightIcon(
                    icon = LightIcons.REWIND,
                    size = 1.5f,
                    modifier = Modifier.clickable(onClick = onPrevious),
                )
            }
            LightIcon(
                icon = if (nowPlaying.isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                size = 2f,
                modifier = Modifier.clickable(onClick = onPlayPause),
            )
            if (nowPlaying.hasNext) {
                LightIcon(
                    icon = LightIcons.FAST_FORWARD,
                    size = 1.5f,
                    modifier = Modifier.clickable(onClick = onNext),
                )
            }
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun LockScreenPreview() {
    LightTheme(colors = LightThemeColors.Dark) {
        LightLockscreen { }
    }
}
