package com.thelightphone.sdk.server.backup

import android.content.SharedPreferences
import com.thelightphone.backup.BackupPreferences
import com.thelightphone.backup.BackupStatus
import com.thelightphone.backup.RemoteBackupProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

// BackupPreferences plus StateFlows so UI can observe changes made by other callers
// (e.g. BackupWorker updating lastBackupStatus) without polling the suspend getters.
interface ObservableBackupPreferences {
    val activeProviderFlow: StateFlow<RemoteBackupProvider?>
    val enabledFlow: StateFlow<Boolean>
    val backupIntervalFlow: StateFlow<Duration>
    val requiresChargingFlow: StateFlow<Boolean>
    val requiresUnmeteredNetworkFlow: StateFlow<Boolean>
    val lastBackupStatusFlow: StateFlow<BackupStatus?>
}

class DefaultObservableBackupPreferences(
    private val androidPrefs: SharedPreferences,
    private val fallbackBackupDuration: Duration = 1.days,
    defaultRequiresCharging: Boolean = true,
    defaultRequiresWifi: Boolean = true
) : ObservableBackupPreferences, BackupPreferences {
    private var enabledFlag = androidPrefs.getBoolean("LIGHT_BACKUP_ENABLED", false)

    private val _activeProvider = MutableStateFlow(readActiveProvider())
    override val activeProviderFlow: StateFlow<RemoteBackupProvider?> = _activeProvider.asStateFlow()

    private val _enabled = MutableStateFlow(enabledFlag && _activeProvider.value != null)
    override val enabledFlow: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _backupInterval = MutableStateFlow(readBackupInterval())
    override val backupIntervalFlow: StateFlow<Duration> = _backupInterval.asStateFlow()

    private val _requiresCharging =
        MutableStateFlow(androidPrefs.getBoolean("LIGHT_BACKUP_REQUIRES_CHARGING", defaultRequiresCharging))
    override val requiresChargingFlow: StateFlow<Boolean> = _requiresCharging.asStateFlow()

    private val _requiresUnmeteredNetwork = MutableStateFlow(
        androidPrefs.getBoolean("LIGHT_BACKUP_REQUIRES_UNMETERED_NETWORK", defaultRequiresWifi)
    )
    override val requiresUnmeteredNetworkFlow: StateFlow<Boolean> = _requiresUnmeteredNetwork.asStateFlow()

    private val _lastBackupStatus = MutableStateFlow(readLastBackupStatus())
    override val lastBackupStatusFlow: StateFlow<BackupStatus?> = _lastBackupStatus.asStateFlow()

    override suspend fun getEnabled(): Boolean = _enabled.value

    override suspend fun setEnabled(enabled: Boolean) {
        enabledFlag = enabled
        androidPrefs.edit().putBoolean("LIGHT_BACKUP_ENABLED", enabled).apply()
        _enabled.value = enabled && _activeProvider.value != null
    }

    override suspend fun getActiveProvider(): RemoteBackupProvider? = _activeProvider.value

    override suspend fun setActiveProvider(provider: RemoteBackupProvider?) {
        val providerChanged = provider != _activeProvider.value
        _activeProvider.value = provider
        androidPrefs.edit().putString("LIGHT_BACKUP_REMOTE_PROVIDER", provider?.name).apply()
        _enabled.value = enabledFlag && provider != null
        // A previous provider's last-run status is meaningless once you switch providers.
        if (providerChanged) {
            clearLastBackupStatus()
        }
    }

    override suspend fun getBackupInterval(): Duration = _backupInterval.value

    override suspend fun setBackupInterval(interval: Duration) {
        _backupInterval.value = interval
        androidPrefs.edit().putLong("LIGHT_BACKUP_INTERVAL_MS", interval.inWholeMilliseconds).apply()
    }

    override suspend fun getRequiresCharging(): Boolean = _requiresCharging.value

    override suspend fun setRequiresCharging(requiresCharging: Boolean) {
        _requiresCharging.value = requiresCharging
        androidPrefs.edit().putBoolean("LIGHT_BACKUP_REQUIRES_CHARGING", requiresCharging).apply()
    }

    override suspend fun getRequiresUnmeteredNetwork(): Boolean = _requiresUnmeteredNetwork.value

    override suspend fun setRequiresUnmeteredNetwork(requiresUnmeteredNetwork: Boolean) {
        _requiresUnmeteredNetwork.value = requiresUnmeteredNetwork
        androidPrefs.edit()
            .putBoolean(
                "LIGHT_BACKUP_REQUIRES_UNMETERED_NETWORK",
                requiresUnmeteredNetwork
            )
            .apply()
    }

    private fun clearLastBackupStatus() {
        _lastBackupStatus.value = null
        androidPrefs.edit()
            .remove("LIGHT_BACKUP_LAST_STATUS_TYPE")
            .remove("LIGHT_BACKUP_LAST_STATUS_COMPLETED_AT")
            .remove("LIGHT_BACKUP_LAST_STATUS_MESSAGE")
            .apply()
    }

    override suspend fun getLastBackupStatus(): BackupStatus? = _lastBackupStatus.value

    override suspend fun setLastBackupStatus(status: BackupStatus) {
        _lastBackupStatus.value = status
        with(androidPrefs.edit()) {
            when (status) {
                is BackupStatus.Succeeded -> {
                    putString("LIGHT_BACKUP_LAST_STATUS_TYPE", "Succeeded")
                    putLong(
                        "LIGHT_BACKUP_LAST_STATUS_COMPLETED_AT",
                        status.completedAt.toEpochMilliseconds()
                    )
                }

                is BackupStatus.Failed -> {
                    putString("LIGHT_BACKUP_LAST_STATUS_TYPE", "Failed")
                    putString("LIGHT_BACKUP_LAST_STATUS_MESSAGE", status.message)
                }

                is BackupStatus.NeedsReauth -> putString(
                    "LIGHT_BACKUP_LAST_STATUS_TYPE",
                    "NeedsReauth"
                )

                is BackupStatus.QuotaExceeded -> putString(
                    "LIGHT_BACKUP_LAST_STATUS_TYPE",
                    "QuotaExceeded"
                )
            }

            apply()
        }
    }

    private fun readLastBackupStatus(): BackupStatus? {
        return when (androidPrefs.getString("LIGHT_BACKUP_LAST_STATUS_TYPE", null)) {
            "Succeeded" -> {
                val completedAtMs =
                    androidPrefs.getLong("LIGHT_BACKUP_LAST_STATUS_COMPLETED_AT", -1L)
                if (completedAtMs < 0) null else BackupStatus.Succeeded(
                    Instant.fromEpochMilliseconds(
                        completedAtMs
                    )
                )
            }

            "Failed" -> BackupStatus.Failed(
                androidPrefs.getString(
                    "LIGHT_BACKUP_LAST_STATUS_MESSAGE",
                    ""
                ).orEmpty()
            )

            "NeedsReauth" -> BackupStatus.NeedsReauth
            "QuotaExceeded" -> BackupStatus.QuotaExceeded
            else -> null
        }
    }

    private fun readActiveProvider(): RemoteBackupProvider? {
        return androidPrefs
            .getString("LIGHT_BACKUP_REMOTE_PROVIDER", null)
            ?.let { stored ->
                RemoteBackupProvider.entries.firstOrNull {
                    it.name.equals(
                        stored,
                        ignoreCase = true
                    )
                }
            }
    }

    private fun readBackupInterval(): Duration {
        return androidPrefs.getLong(
            "LIGHT_BACKUP_INTERVAL_MS",
            fallbackBackupDuration.inWholeMilliseconds
        )
            .toDuration(DurationUnit.MILLISECONDS)
    }
}