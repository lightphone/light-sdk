package com.thelightphone.sdk.emulator.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.thelightphone.backup.BackupDataSource
import com.thelightphone.backup.BackupDependencyProvider
import com.thelightphone.backup.BackupPreferences
import com.thelightphone.backup.BackupStatus
import com.thelightphone.sdk.server.toolmanager.OAuthTunnelClient
import com.thelightphone.backup.RemoteAccessTokenProvider
import com.thelightphone.backup.RemoteBackup
import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.sdk.emulator.BuildConfig
import com.thelightphone.sdk.server.backup.CompositeDataSource
import com.thelightphone.sdk.server.backup.LightSdkToolsBackupDataSource
import com.thelightphone.sdk.server.backup.SmsMmsBackupDataSource
import com.thelightphone.sdk.server.toolmanager.StoredOAuthTokenProvider
import com.thelightphone.sdk.server.toolmanager.StoredOAuthTokens
import com.thelightphone.sdk.server.toolmanager.TokenStorage
import com.thelightphone.sdk.server.toolmanager.refreshAccessToken
import com.thelightphone.toolmanager.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

class EmulatorBackupDependencyProvider(
    private val appContext: Context,
    private val logger: Logger,
    private val rootPath: String
) : BackupDependencyProvider {

    val tokenStorage: TokenStorage by lazy {
        EmulatorTokenStorage(
            appContext.getSharedPreferences(
                "light_backup_tokens",
                Context.MODE_PRIVATE
            )
        )
    }

    override fun createTokenProvider(provider: RemoteBackupProvider): RemoteAccessTokenProvider? {
        val tunnelClient = createOAuthTunnelClient(provider) ?: return null
        return StoredOAuthTokenProvider(provider.name, tokenStorage) { refreshToken ->
            tunnelClient.refreshAccessToken(refreshToken)
        }
    }

    override fun buildCustomRemoteBackup(
        remoteAccessTokenProvider: RemoteAccessTokenProvider,
        rootFilePath: String
    ): RemoteBackup {
        return EmulatorRemoteBackup(remoteAccessTokenProvider, rootFilePath)
    }

    override fun createDataSource(): BackupDataSource {
        // TODO add user switches per tool
        return CompositeDataSource(
            listOf(
                LightSdkToolsBackupDataSource(appContext, logger),
                SmsMmsBackupDataSource(appContext, logger),
            ),
            logger,
        )
    }

    private fun createOAuthTunnelClient(provider: RemoteBackupProvider): OAuthTunnelClient? {
        val workerHost = BuildConfig.BACKUP_WORKER_HOST
        return when (provider) {
            RemoteBackupProvider.Custom -> EmulatorRelayOAuthTunnelClient(workerHost)
            else -> null
        }
    }

    override fun rootFolderPath(): String = rootPath
}

class EmulatorTokenStorage(private val androidPrefs: SharedPreferences) : TokenStorage {
    override suspend fun saveOAuthDetails(
        accountType: String,
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
        scope: String
    ) {
        androidPrefs.edit {
            putString("$accountType.accessToken", accessToken)
            putString("$accountType.refreshToken", refreshToken)
            putLong("$accountType.expiresAt", expiresAt.toEpochMilliseconds())
            putString("$accountType.scope", scope)
        }
    }

    override suspend fun getOAuthDetails(accountType: String): StoredOAuthTokens? {
        val accessToken = androidPrefs.getString("$accountType.accessToken", null) ?: return null
        val refreshToken = androidPrefs.getString("$accountType.refreshToken", null) ?: return null
        val expiresAtMs = androidPrefs.getLong("$accountType.expiresAt", -1L)
        if (expiresAtMs < 0) return null
        val scope = androidPrefs.getString("$accountType.scope", null) ?: return null
        return StoredOAuthTokens(
            accessToken,
            refreshToken,
            Instant.fromEpochMilliseconds(expiresAtMs),
            scope
        )
    }

    override suspend fun removeOAuthDetails(accountType: String): Boolean {
        val existed = androidPrefs.contains("$accountType.accessToken")
        androidPrefs.edit {
            remove("$accountType.accessToken")
            remove("$accountType.refreshToken")
            remove("$accountType.expiresAt")
            remove("$accountType.scope")
        }
        return existed
    }
}

class EmulatorBackupPreferences(
    private val androidPrefs: SharedPreferences,
    private val fallbackBackupDuration: Duration = 1.days,
    private val defaultRequiresCharging: Boolean = true,
    private val defaultRequiresWifi: Boolean = true
) : BackupPreferences {
    override suspend fun getEnabled(): Boolean {
        return getActiveProvider() != null && androidPrefs.getBoolean("LIGHT_BACKUP_ENABLED", false)
    }

    override suspend fun setEnabled(enabled: Boolean) {
        androidPrefs.edit { putBoolean("LIGHT_BACKUP_ENABLED", enabled) }
    }

    override suspend fun getActiveProvider(): RemoteBackupProvider? {
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

    override suspend fun setActiveProvider(provider: RemoteBackupProvider?) {
        androidPrefs.edit { putString("LIGHT_BACKUP_REMOTE_PROVIDER", provider?.name) }
    }

    override suspend fun getBackupInterval(): Duration {
        return androidPrefs.getLong(
            "LIGHT_BACKUP_INTERVAL_MS",
            fallbackBackupDuration.inWholeMilliseconds
        )
            .toDuration(DurationUnit.MILLISECONDS)
    }

    override suspend fun setBackupInterval(interval: Duration) {
        androidPrefs.edit { putLong("LIGHT_BACKUP_INTERVAL_MS", interval.inWholeMilliseconds) }
    }

    override suspend fun getRequiresCharging(): Boolean {
        return androidPrefs.getBoolean("LIGHT_BACKUP_REQUIRES_CHARGING", defaultRequiresCharging)
    }

    override suspend fun setRequiresCharging(requiresCharging: Boolean) {
        androidPrefs.edit { putBoolean("LIGHT_BACKUP_REQUIRES_CHARGING", requiresCharging) }
    }

    override suspend fun getRequiresUnmeteredNetwork(): Boolean {
        return androidPrefs.getBoolean(
            "LIGHT_BACKUP_REQUIRES_UNMETERED_NETWORK",
            defaultRequiresWifi
        )
    }

    override suspend fun setRequiresUnmeteredNetwork(requiresUnmeteredNetwork: Boolean) {
        androidPrefs.edit {
            putBoolean(
                "LIGHT_BACKUP_REQUIRES_UNMETERED_NETWORK",
                requiresUnmeteredNetwork
            )
        }
    }

    override suspend fun getLastBackupStatus(): BackupStatus? {
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

    override suspend fun setLastBackupStatus(status: BackupStatus) {
        androidPrefs.edit {
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
        }
    }

}
