package com.thelightphone.sdk.server.backup

import com.thelightphone.backup.BackupPreferences
import com.thelightphone.backup.BackupScheduler
import com.thelightphone.sdk.server.LightSdkServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

enum class BackupIntervalOption {
    None, Nightly
}

data class BackupAllowedStatus(val packageName: String, val label: String, val allowed: Boolean)

interface BackupSettingsViewModel {
    suspend fun onEnabledChanged(enabled: Boolean)
    suspend fun onFrequencySelected(interval: BackupIntervalOption)
    suspend fun onlyOnWifiToggled(enabled: Boolean)
    suspend fun onlyWhileChargingToggled(enabled: Boolean)
    fun launchToolManagerForLogin()
    suspend fun setBackupAllowedForPackage(packageName: String, backupAllowed: Boolean)
    val backupAllowList: StateFlow<List<BackupAllowedStatus>>
    val backupPreferences: ObservableBackupPreferences
    val isBackupRunning: StateFlow<Boolean>

    fun runBackupNow()
}

class DefaultBackupSettingsViewModel(
    override val backupPreferences: DefaultObservableBackupPreferences,
    val allowList: BackupPackageAllowList,
    val getBackupCapablePackages: suspend () -> List<BackupCapableTool>,
    val labelForPackage: (String) -> String,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : BackupSettingsViewModel {

    private val _backupAllowList = MutableStateFlow<List<BackupAllowedStatus>>(emptyList())
    override val backupAllowList: StateFlow<List<BackupAllowedStatus>> = _backupAllowList.asStateFlow()

    private val _isBackupRunning = MutableStateFlow(false)
    override val isBackupRunning: StateFlow<Boolean> = _isBackupRunning.asStateFlow()

    init {
        scope.launch { populateBackupAllowList() }
    }

    override suspend fun onEnabledChanged(enabled: Boolean) {
        backupPreferences.setEnabled(enabled)
    }

    override suspend fun onFrequencySelected(interval: BackupIntervalOption) {
        val nextInterval = when (interval) {
            BackupIntervalOption.None -> Duration.INFINITE
            BackupIntervalOption.Nightly -> 1.days
        }
        backupPreferences.setBackupInterval(nextInterval)
    }

    override suspend fun onlyOnWifiToggled(enabled: Boolean) {
        backupPreferences.setRequiresUnmeteredNetwork(enabled)
    }

    override suspend fun onlyWhileChargingToggled(enabled: Boolean) {
        backupPreferences.setRequiresCharging(enabled)
    }

    override fun launchToolManagerForLogin() = Unit // TODO

    override fun runBackupNow() {
//        BackupScheduler.enqueueImmediateBackup()
    }

    private suspend fun populateBackupAllowList() {
        _backupAllowList.value = getBackupCapablePackages()
            .map { it to labelForPackage(it.packageName) }
            .map { (tool, label) ->
                BackupAllowedStatus(tool.packageName, label, allowList.userAllowedTool(tool))
            }
    }

    override suspend fun setBackupAllowedForPackage(
        packageName: String,
        backupAllowed: Boolean
    ) {
        allowList.setPackageAllowed(packageName, backupAllowed)
        populateBackupAllowList()
    }
}