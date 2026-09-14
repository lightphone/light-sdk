package com.thelightphone.sdk.emulator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.thelightphone.backup.BackupPreferences
import com.thelightphone.backup.RemoteBackupProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration

// BackupPreferences' getters/setters are all suspend fun, so a composable can't just read them
// straight into remember { mutableStateOf(...) } the way EmulatorSettings does for the
// synchronous LightSdkServerSettings. This loads every field once, then writes through to
// backupPreferences (and updates local state optimistically) on every change - the compose-side
// values displayed by BackupSettings never wait on the underlying suspend calls.
@Stable
class BackupPreferencesState(
    private val backupPreferences: BackupPreferences,
    private val scope: CoroutineScope,
) {
    var enabled by mutableStateOf<Boolean?>(null)
        private set
    var activeProvider by mutableStateOf<RemoteBackupProvider?>(null)
        private set
    var backupInterval by mutableStateOf(Duration.ZERO)
        private set
    var requiresCharging by mutableStateOf(true)
        private set
    var requiresUnmeteredNetwork by mutableStateOf(true)
        private set

    suspend fun load() {
        enabled = backupPreferences.getEnabled()
        activeProvider = backupPreferences.getActiveProvider()
        backupInterval = backupPreferences.getBackupInterval()
        requiresCharging = backupPreferences.getRequiresCharging()
        requiresUnmeteredNetwork = backupPreferences.getRequiresUnmeteredNetwork()
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
        scope.launch { backupPreferences.setEnabled(value) }
    }

    fun updateActiveProvider(provider: RemoteBackupProvider?) {
        activeProvider = provider
        scope.launch { backupPreferences.setActiveProvider(provider) }
    }

    fun updateBackupInterval(interval: Duration) {
        backupInterval = interval
        scope.launch { backupPreferences.setBackupInterval(interval) }
    }

    fun updateRequiresCharging(value: Boolean) {
        requiresCharging = value
        scope.launch { backupPreferences.setRequiresCharging(value) }
    }

    fun updateRequiresUnmeteredNetwork(value: Boolean) {
        requiresUnmeteredNetwork = value
        scope.launch { backupPreferences.setRequiresUnmeteredNetwork(value) }
    }
}

@Composable
fun rememberBackupPreferencesState(backupPreferences: BackupPreferences): BackupPreferencesState {
    val scope = rememberCoroutineScope()
    val state = remember(backupPreferences) { BackupPreferencesState(backupPreferences, scope) }
    LaunchedEffect(backupPreferences) { state.load() }
    return state
}
