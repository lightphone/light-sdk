package com.thelightphone.sdk.emulator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.ForceFocusLevel
import com.thelightphone.sdk.server.LightSdkServerSettings
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.*
import com.thelightphone.sdk.ui.LightBarButton.LightIcon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EmulatorSettingsNav {
    Root, FilterLevel, Keyboard, ForceFocus, Notifications
}

val ClientFilterLevel.label: String
    get() = when (this) {
        ClientFilterLevel.ExcludeAllApks -> "Default Only"
        ClientFilterLevel.AllowLightApprovedApks -> "Community Tools"
        ClientFilterLevel.AllowLightSignedApks -> "Built with SDK"
        ClientFilterLevel.AllowAllApks -> "All Tools"
    }

val ForceFocusLevel.label: String
    get() = when (this) {
        ForceFocusLevel.Always -> "Always Foreground Emulator"
        ForceFocusLevel.AlertsOnly -> "Foreground Alerts Only"
        ForceFocusLevel.Never -> "Never Foreground Emulator"
    }

@Composable
fun EmulatorSettings(
    settings: LightSdkServerSettings,
    emulatorSettingsAudio: EmulatorSettingsAudio,
    startingNav: Nav.Settings,
    onRootBackPressed: () -> Unit,
) {
    var nav by remember { mutableStateOf(startingNav.startingScreen) }
    val subscreenBackPressed = startingNav.backButtonOverride ?: {
        nav = EmulatorSettingsNav.Root
    }
    val rowPadding = Modifier.padding(1f.gridUnitsAsDp())
    Surface(Modifier.fillMaxSize()) {
        when (nav) {
            EmulatorSettingsNav.Root -> {
                Column(Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightIcon(
                            icon = LightIcons.BACK,
                            onClick = startingNav.backButtonOverride ?: onRootBackPressed
                        ),
                        center = LightTopBarCenter.Text("Settings"),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav = EmulatorSettingsNav.FilterLevel }
                            .then(rowPadding)
                    ) {
                        Column {
                            LightText("Allowed Tools", variant = LightTextVariant.Superfine)
                            LightText(
                                settings.clientFilterLevel.label,
                                variant = LightTextVariant.Copy
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav = EmulatorSettingsNav.ForceFocus }
                            .then(rowPadding)
                    ) {
                        Column {
                            LightText("Force Focus", variant = LightTextVariant.Superfine)
                            LightText(
                                settings.forceFocusLevel.label,
                                variant = LightTextVariant.Copy
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav = EmulatorSettingsNav.Keyboard }
                            .then(rowPadding)
                    ) {
                        Column {
                            LightText(
                                "Keyboard Settings",
                                variant = LightTextVariant.Copy
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav = EmulatorSettingsNav.Notifications }
                            .then(rowPadding)
                    ) {
                        Column {
                            LightText("Notifications", variant = LightTextVariant.Copy)
                        }
                    }
                }
            }

            EmulatorSettingsNav.FilterLevel -> ClientFilterLevelSettings(
                settings,
                subscreenBackPressed
            )

            EmulatorSettingsNav.ForceFocus -> ForceFocusLevelSettings(
                settings,
                subscreenBackPressed
            )

            EmulatorSettingsNav.Keyboard -> KeyboardSettings(settings, subscreenBackPressed)

            EmulatorSettingsNav.Notifications -> {
                val volume by emulatorSettingsAudio.ringerVolume.collectAsState()
                NotificationSettings(
                    volume = volume,
                    onVolumeChange = emulatorSettingsAudio::setRingerVolume,
                    onBackPressed = subscreenBackPressed,
                )
            }
        }
    }
}

@Composable
fun ClientFilterLevelSettings(settings: LightSdkServerSettings, onBackPressed: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBackPressed
                ),
                center = LightTopBarCenter.Text("Allowed Tools"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            for (clientFilterLevel in ClientFilterLevel.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            settings.clientFilterLevel = clientFilterLevel
                            onBackPressed()
                        }
                        .padding(16.dp)
                ) {
                    LightText(
                        clientFilterLevel.label,
                        variant = LightTextVariant.Subheading
                    )
                }
            }
        }
    }
}

@Composable
fun ForceFocusLevelSettings(settings: LightSdkServerSettings, onBackPressed: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBackPressed
                ),
                center = LightTopBarCenter.Text("Force Focus Level"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            for (forceFocusLevel in ForceFocusLevel.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            settings.forceFocusLevel = forceFocusLevel
                            onBackPressed()
                        }
                        .padding(16.dp)
                ) {
                    LightText(
                        forceFocusLevel.label,
                        variant = LightTextVariant.Subheading
                    )
                }
            }
        }
    }
}

@Composable
fun NotificationSettings(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onBackPressed: () -> Unit,
) {
    val themeColors = LightThemeController.colors.collectAsState().value
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightIcon(icon = LightIcons.BACK, onClick = onBackPressed),
                center = LightTopBarCenter.Text("Notifications"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            Column(Modifier.padding(horizontal = 3.5f.gridUnitsAsDp())) {
                LightText("Ringer volume", variant = LightTextVariant.Superfine)
                LightTouchableProgressBar(
                    colors = themeColors,
                    progress = volume,
                    onValueChange = onVolumeChange,
                )
            }
        }
    }
}

@Composable
fun KeyboardSettings(settings: LightSdkServerSettings, onBackPressed: () -> Unit) {
    var keyboardOptions by remember { mutableStateOf(settings.keyboardOptions) }
    fun updateOptions(newOptions: LightServiceMethod.GetKeyboardOptions.Response) {
        settings.keyboardOptions = newOptions
        keyboardOptions = newOptions
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBackPressed
                ),
                center = LightTopBarCenter.Text("Keyboard Settings"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            Column(Modifier.padding(horizontal = 16.dp)) {
                LightText(
                    text = when (keyboardOptions.enableKeyAnimation) {
                        true -> "KEY ANIMATION: ON"
                        false -> "KEY ANIMATION: OFF"
                    },
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .clickable {
                            updateOptions(keyboardOptions.copy(enableKeyAnimation = !keyboardOptions.enableKeyAnimation))
                        }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )

                LightText(
                    text = when (keyboardOptions.displayVoice) {
                        true -> "SHOW VOICE KEY: ON"
                        false -> "SHOW VOICE KEY: OFF"
                    },
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .clickable {
                            updateOptions(keyboardOptions.copy(displayVoice = !keyboardOptions.displayVoice))
                        }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )
            }
        }
    }
}

interface EmulatorSettingsAudio {
    fun setRingerVolume(normalized: Float)
    val ringerVolume: StateFlow<Float>
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun EmulatorSettingsPreview() {
    val settings = object : LightSdkServerSettings {
        override var clientFilterLevel: ClientFilterLevel = ClientFilterLevel.AllowAllApks
        override var keyboardOptions: LightServiceMethod.GetKeyboardOptions.Response =
            LightServiceMethod.GetKeyboardOptions.Response(null,
                displayVoice = true,
                enableKeyAnimation = true,
                swipeEnabled = true
            )
        override var userPreferences: LightServiceMethod.GetUserPreferences.Response =
            LightServiceMethod.GetUserPreferences.Response(hapticsEnabled = true)
        override var forceFocusLevel: ForceFocusLevel = ForceFocusLevel.Always
    }
    val emulatorAudioWrapper = object : EmulatorSettingsAudio {
        private val currentVolume = MutableStateFlow(0.5f)
        override fun setRingerVolume(normalized: Float) {
            currentVolume.value = normalized
        }

        override val ringerVolume: StateFlow<Float> = currentVolume.asStateFlow()
    }
    LightTheme {
        EmulatorSettings(settings, emulatorAudioWrapper, Nav.Settings()) { }
    }
}