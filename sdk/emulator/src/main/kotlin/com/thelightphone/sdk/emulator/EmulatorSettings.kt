package com.thelightphone.sdk.emulator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.emulator.backup.BackupSettings
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.ForceFocusLevel
import com.thelightphone.sdk.server.LightSdkServerSettings
import com.thelightphone.sdk.server.backup.BackupSettingsViewModel
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBarButton.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.StateFlow

enum class EmulatorSettingsNav {
    Root, FilterLevel, Keyboard, ForceFocus, Notifications, Backups
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
    backupSettingsViewModel: BackupSettingsViewModel,
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
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav = EmulatorSettingsNav.Backups }
                            .then(rowPadding)
                    ) {
                        Column {
                            LightText("Backup", variant = LightTextVariant.Copy)
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

            EmulatorSettingsNav.Backups -> {
                BackupSettings(
                    viewModel = backupSettingsViewModel,
                    onBackClick = subscreenBackPressed,
                    providerName = { name },
                )
            }

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