package com.thelightphone.sdk.emulator.backup

import android.content.Context
import com.thelightphone.backup.RemoteAccessTokenProvider
import com.thelightphone.backup.RemoteBackup
import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.sdk.emulator.BuildConfig
import com.thelightphone.sdk.server.backup.BaseBackupDependencyProvider
import com.thelightphone.sdk.server.toolmanager.OAuthTunnelClient
import com.thelightphone.toolmanager.KeyCipher
import com.thelightphone.toolmanager.Logger

class DefaultBackupDependencyProvider(
    appContext: Context,
    logger: Logger,
    keyCipher: KeyCipher,
    rootPath: String,
) : BaseBackupDependencyProvider(appContext, logger, keyCipher, rootPath) {

    override fun buildCustomRemoteBackup(
        remoteAccessTokenProvider: RemoteAccessTokenProvider,
        rootFilePath: String
    ): RemoteBackup {
        return EmulatorRemoteBackup(remoteAccessTokenProvider, rootFilePath)
    }

    override fun createOAuthTunnelClient(provider: RemoteBackupProvider): OAuthTunnelClient? {
        val workerHost = BuildConfig.BACKUP_WORKER_HOST
        return when (provider) {
            RemoteBackupProvider.Custom -> EmulatorRelayOAuthTunnelClient(workerHost)
            else -> null
        }
    }
}
