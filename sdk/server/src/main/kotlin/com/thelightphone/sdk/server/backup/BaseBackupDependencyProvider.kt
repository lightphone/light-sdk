package com.thelightphone.sdk.server.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.thelightphone.backup.BackupDataSource
import com.thelightphone.backup.BackupDependencyProvider
import com.thelightphone.backup.BackupPreferences
import com.thelightphone.backup.BackupStatus
import com.thelightphone.backup.RemoteAccessTokenProvider
import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.sdk.server.toolmanager.OAuthTunnelClient
import com.thelightphone.sdk.server.toolmanager.StoredOAuthTokenProvider
import com.thelightphone.sdk.server.toolmanager.StoredOAuthTokens
import com.thelightphone.sdk.server.toolmanager.TokenStorage
import com.thelightphone.sdk.server.toolmanager.refreshAccessToken
import com.thelightphone.toolmanager.KeyCipher
import com.thelightphone.toolmanager.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.collections.sorted
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

data class BackupCapableTool(val packageName: String, val internalTool: Boolean)
abstract class BaseBackupDependencyProvider(
    private val appContext: Context,
    private val logger: Logger,
    private val keyCipher: KeyCipher,
    private val rootPath: String,
    // map of "package name" (any unique id for internal tools since they're not APKs)
    // to BackupDataSource for that tool
    private val internalToolsBackupSpecs: List<InternalToolBackupSpec> = listOf(InternalToolBackupSpec.Messages)
) : BackupDependencyProvider {

    // provides backups for all external (third-party) tools
    private val sdkBackupSource by lazy { LightSdkToolsBackupDataSource(appContext, logger) }
    private val preferences = appContext.getSharedPreferences(
        "light_backup",
        Context.MODE_PRIVATE
    )

    val tokenStorage: TokenStorage by lazy {
        DefaultTokenStorage(preferences, keyCipher)
    }

    suspend fun getBackupCapableTools(): List<BackupCapableTool> {
        val external = sdkBackupSource.getBackupCapableTools().map {
            BackupCapableTool(it.packageName, false)
        }
        val interval = internalToolsBackupSpecs.map {
            BackupCapableTool(it.packageName, true)
        }
        return (external + interval).sortedBy { it.packageName }
    }

    override fun createDataSource(): BackupDataSource {
        return CompositeDataSource(
            internalToolsBackupSpecs.map { it.dataSourceFactory(appContext, logger) }
                    + sdkBackupSource,
            logger
        )
    }

    protected abstract fun createOAuthTunnelClient(provider: RemoteBackupProvider): OAuthTunnelClient?

    override fun createTokenProvider(provider: RemoteBackupProvider): RemoteAccessTokenProvider? {
        val tunnelClient = createOAuthTunnelClient(provider) ?: return null
        return StoredOAuthTokenProvider(provider.name, tokenStorage) { refreshToken ->
            tunnelClient.refreshAccessToken(refreshToken)
        }
    }

    override fun rootFolderPath(): String = rootPath
}

class DefaultTokenStorage(
    private
    val androidPrefs: SharedPreferences,
    private val cipher: KeyCipher,
) : TokenStorage {
    override suspend fun saveOAuthDetails(
        accountType: String,
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
        scope: String
    ) {
        with(androidPrefs.edit()) {
            putString("$accountType.accessToken", cipher.encryptToString(accessToken))
            putString("$accountType.refreshToken", cipher.encryptToString(refreshToken))
            putLong("$accountType.expiresAt", expiresAt.toEpochMilliseconds())
            putString("$accountType.scope", scope)
            apply()
        }
    }

    override suspend fun getOAuthDetails(accountType: String): StoredOAuthTokens? {
        // A decrypt failure (e.g. the Keystore key was invalidated by a lock screen change)
        // is treated the same as no stored tokens, forcing the caller down the re-auth path
        // instead of crashing.
        val accessToken = androidPrefs.getString("$accountType.accessToken", null)
            ?.let { cipher.decryptStringOrNull(it) } ?: return null
        val refreshToken = androidPrefs.getString("$accountType.refreshToken", null)
            ?.let { cipher.decryptStringOrNull(it) } ?: return null
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
        with(androidPrefs.edit()) {
            remove("$accountType.accessToken")
            remove("$accountType.refreshToken")
            remove("$accountType.expiresAt")
            remove("$accountType.scope")
            apply()
        }
        return existed
    }

    private fun KeyCipher.encryptToString(plaintext: String): String =
        Base64.encodeToString(encrypt(plaintext), Base64.NO_WRAP)

    private fun KeyCipher.decryptStringOrNull(encoded: String): String? = try {
        decrypt(Base64.decode(encoded, Base64.NO_WRAP))
    } catch (e: Exception) {
        Log.w(TAG, "Failed to decrypt stored OAuth token, treating as unlinked", e)
        null
    }

    private companion object {
        const val TAG = "EmulatorTokenStorage"
    }
}

