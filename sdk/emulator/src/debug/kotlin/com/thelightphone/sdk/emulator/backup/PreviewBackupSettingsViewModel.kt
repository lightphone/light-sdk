package com.thelightphone.sdk.emulator.backup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.thelightphone.backup.BackupStatus
import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.sdk.server.backup.BackupAllowedStatus
import com.thelightphone.sdk.server.backup.BackupIntervalOption
import com.thelightphone.sdk.server.backup.BackupSettingsViewModel
import com.thelightphone.sdk.server.backup.ObservableBackupPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

// Shared fake used by @Preview composables for BackupSettingsUi and EmulatorSettings.
// Lives in src/debug alongside its callers so none of it ships in a release build.
@Composable
fun rememberPreviewBackupSettingsViewModel(): BackupSettingsViewModel {
    val previewScope = rememberCoroutineScope()
    return remember {
        val previewPreferences = object : ObservableBackupPreferences {
            override val enabledFlow = MutableStateFlow(true)
            override val activeProviderFlow = MutableStateFlow<RemoteBackupProvider?>(RemoteBackupProvider.Google)
            override val backupIntervalFlow = MutableStateFlow(1.days)
            override val requiresChargingFlow = MutableStateFlow(true)
            override val requiresUnmeteredNetworkFlow = MutableStateFlow(true)
            override val lastBackupStatusFlow =
                MutableStateFlow<BackupStatus?>(BackupStatus.Succeeded(Clock.System.now()))
        }
        val previewAllowList = MutableStateFlow(
            listOf(
                BackupAllowedStatus("com.thelightphone.notes", "Notes", allowed = true),
                BackupAllowedStatus("com.thelightphone.camera", "Camera", allowed = true),
                BackupAllowedStatus("com.thelightphone.messages", "Messages", allowed = false),
                BackupAllowedStatus("com.thelightphone.music", "Music", allowed = false),
            )
        )
        val previewIsBackupRunning = MutableStateFlow(false)
        object : BackupSettingsViewModel {
            override val backupAllowList: StateFlow<List<BackupAllowedStatus>> = previewAllowList
            override val backupPreferences: ObservableBackupPreferences = previewPreferences
            override val isBackupRunning: StateFlow<Boolean> = previewIsBackupRunning

            override suspend fun onEnabledChanged(enabled: Boolean) {
                previewPreferences.enabledFlow.value = enabled
            }
            override suspend fun onFrequencySelected(interval: BackupIntervalOption) {
                previewPreferences.backupIntervalFlow.value =
                    if (interval == BackupIntervalOption.Nightly) 1.days else Duration.INFINITE
            }
            override suspend fun onlyOnWifiToggled(enabled: Boolean) {
                previewPreferences.requiresUnmeteredNetworkFlow.value = enabled
            }
            override suspend fun onlyWhileChargingToggled(enabled: Boolean) {
                previewPreferences.requiresChargingFlow.value = enabled
            }
            override fun launchToolManagerForLogin() = Unit

            // Simulates a backup run so previews can demonstrate the disabled button state.
            override fun runBackupNow() {
                if (previewIsBackupRunning.value) return
                previewScope.launch {
                    previewIsBackupRunning.value = true
                    delay(2000)
                    previewPreferences.lastBackupStatusFlow.value = BackupStatus.Succeeded(Clock.System.now())
                    previewIsBackupRunning.value = false
                }
            }

            override suspend fun setBackupAllowedForPackage(packageName: String, backupAllowed: Boolean) {
                previewAllowList.value = previewAllowList.value.map {
                    if (it.packageName == packageName) it.copy(allowed = backupAllowed) else it
                }
            }
        }
    }
}
