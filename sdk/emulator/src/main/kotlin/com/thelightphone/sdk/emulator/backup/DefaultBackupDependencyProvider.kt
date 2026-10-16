package com.thelightphone.sdk.emulator.backup

import android.content.Context
import com.thelightphone.backup.RemoteBackup
import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.backup.RemoteConnection
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

    // the dummy cloud only speaks OAuth, anything else means its account isn't linked
    override fun buildCustomRemoteBackup(
        connection: RemoteConnection?,
        rootFolderPath: String
    ): RemoteBackup? {
        val oAuth = connection as? RemoteConnection.OAuth ?: return null
        return EmulatorRemoteBackup(oAuth.tokenProvider, rootFolderPath)
    }

    override fun createOAuthTunnelClient(provider: RemoteBackupProvider): OAuthTunnelClient? {
        val workerHost = BuildConfig.BACKUP_WORKER_HOST
        return when (provider) {
            RemoteBackupProvider.Custom -> EmulatorRelayOAuthTunnelClient(workerHost)
            else -> null
        }
    }
}
