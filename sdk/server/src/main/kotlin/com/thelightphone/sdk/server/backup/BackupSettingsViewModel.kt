package com.thelightphone.sdk.server.backup

import android.app.Activity
import com.thelightphone.backup.BackupStatus
import com.thelightphone.backup.RemoteBackupProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    fun launchToolManagerForLogin(activity: Activity)
    suspend fun signOut()
    suspend fun setBackupAllowedForPackage(packageName: String, backupAllowed: Boolean)
    val backupAllowList: StateFlow<List<BackupAllowedStatus>>
    val backupPreferences: ObservableBackupPreferences
    val isBackupRunning: StateFlow<Boolean>

    fun runBackupNow()
}

abstract class DefaultBackupSettingsViewModel(
    override val backupPreferences: DefaultObservableBackupPreferences,
    val allowList: BackupPackageAllowList,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : BackupSettingsViewModel {

    protected abstract suspend fun getBackupCapablePackages(): List<BackupCapableTool>
    protected abstract fun labelForPackage(packageName: String): String
    protected abstract fun kickoffBackupWorker()
    protected abstract suspend fun removeStoredCredentials(provider: RemoteBackupProvider)
    // Stops any backup that's queued or running (periodic or manual).
    protected abstract suspend fun cancelBackupWork()

    private val _backupAllowList = MutableStateFlow<List<BackupAllowedStatus>>(emptyList())
    override val backupAllowList: StateFlow<List<BackupAllowedStatus>> = _backupAllowList.asStateFlow()

    override val isBackupRunning: StateFlow<Boolean> = backupPreferences.lastBackupStatusFlow
        .map { it is BackupStatus.InProgress }
        .stateIn(scope, SharingStarted.Eagerly, backupPreferences.lastBackupStatusFlow.value is BackupStatus.InProgress)

    init {
        scope.launch { populateBackupAllowList() }
    }

    override suspend fun onEnabledChanged(enabled: Boolean) {
        backupPreferences.setEnabled(enabled)
        if (!enabled) cancelBackupWork()
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

    override fun runBackupNow() {
        // enabledFlow is only true when a provider is linked
        if (backupPreferences.enabledFlow.value) kickoffBackupWorker()
    }

    override suspend fun signOut() {
        val provider = backupPreferences.getActiveProvider() ?: return
        // Cancel first so a running backup can't keep using (or fail on) the credentials we remove.
        cancelBackupWork()
        removeStoredCredentials(provider)
        backupPreferences.setActiveProvider(null)
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