package com.thelightphone.sdk.emulator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.thelightphone.backup.BackupPreferences
import com.thelightphone.backup.BackupScheduler
import com.thelightphone.backup.BackupStatus
import com.thelightphone.backup.BackupWorker
import com.thelightphone.backup.BackupWorkerFactory
import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.ForceFocusLevel
import com.thelightphone.sdk.server.LightSdkServerSettings
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.*
import com.thelightphone.sdk.ui.LightBarButton.LightIcon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
    backupPreferences: BackupPreferences,
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

            EmulatorSettingsNav.Backups -> BackupSettings(backupPreferences, subscreenBackPressed)

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

// Fixed set of presets to cycle through on tap - BackupPreferences.getBackupInterval's docs note
// WorkManager enforces a 15-minute floor on periodic work anyway, so free-text entry wouldn't buy
// anything a short preset list doesn't already cover.
private val backupIntervalPresets = listOf(15.minutes, 30.minutes, 1.hours, 6.hours, 1.days)

private fun nextBackupInterval(current: Duration): Duration {
    val index = backupIntervalPresets.indexOf(current)
    return backupIntervalPresets[(index + 1).mod(backupIntervalPresets.size)]
}

private val backupProviderOptions: List<RemoteBackupProvider?> = listOf(null) + RemoteBackupProvider.entries

private fun nextBackupProvider(current: RemoteBackupProvider?): RemoteBackupProvider? {
    val index = backupProviderOptions.indexOf(current)
    return backupProviderOptions[(index + 1).mod(backupProviderOptions.size)]
}

@Composable
fun BackupSettings(backupPreferences: BackupPreferences, onBackPressed: () -> Unit) {
    val state = rememberBackupPreferencesState(backupPreferences)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBackPressed
                ),
                center = LightTopBarCenter.Text("Backup Settings"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            Column(Modifier.padding(horizontal = 16.dp)) {
                LightText(
                    text = if (state.enabled == true) "BACKUP ENABLED: ON" else "BACKUP ENABLED: OFF",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .clickable { state.updateEnabled(state.enabled != true) }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )

                LightText(
                    text = "PROVIDER: ${state.activeProvider?.name ?: "None"}",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .clickable { state.updateActiveProvider(nextBackupProvider(state.activeProvider)) }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )

                LightText(
                    text = "BACKUP INTERVAL: ${state.backupInterval}",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .clickable { state.updateBackupInterval(nextBackupInterval(state.backupInterval)) }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )

                LightText(
                    text = if (state.requiresCharging) "REQUIRES CHARGING: ON" else "REQUIRES CHARGING: OFF",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .clickable { state.updateRequiresCharging(!state.requiresCharging) }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )

                LightText(
                    text = if (state.requiresUnmeteredNetwork) "REQUIRES WIFI: ON" else "REQUIRES WIFI: OFF",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .clickable { state.updateRequiresUnmeteredNetwork(!state.requiresUnmeteredNetwork) }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )

                val context = LocalContext.current
                LightText(
                    text = "Run now",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .clickable {
                            BackupScheduler.enqueueImmediateBackup(context)
                        }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
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
            LightServiceMethod.GetKeyboardOptions.Response(
                null,
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

    val backupPreferences = object : BackupPreferences {
        override suspend fun getEnabled(): Boolean = false
        override suspend fun setEnabled(enabled: Boolean) {}
        override suspend fun getActiveProvider(): RemoteBackupProvider? = null
        override suspend fun setActiveProvider(provider: RemoteBackupProvider?) {}
        override suspend fun getBackupInterval(): Duration = 1.days
        override suspend fun setBackupInterval(interval: Duration) {}
        override suspend fun getRequiresCharging(): Boolean = true
        override suspend fun setRequiresCharging(requiresCharging: Boolean) {}
        override suspend fun getRequiresUnmeteredNetwork(): Boolean = true
        override suspend fun setRequiresUnmeteredNetwork(requiresUnmeteredNetwork: Boolean) {}
        override suspend fun getLastBackupStatus(): BackupStatus? = null
        override suspend fun setLastBackupStatus(status: BackupStatus) {}
    }
    LightTheme {
        EmulatorSettings(settings, emulatorAudioWrapper, backupPreferences, Nav.Settings()) { }
    }
}