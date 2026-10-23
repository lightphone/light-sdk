package com.thelightphone.sdk.emulator.backup

import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.sdk.server.toolmanager.OAuthJobDataTree
import com.thelightphone.sdk.server.toolmanager.TokenStorage
import com.thelightphone.toolmanager.JobSpec
import com.thelightphone.toolmanager.LeafView

// tool manager leaf for the emulator's own custom cloud backup (which is just a local server)
fun dummyCloudBackupLeafView(
    tokenStorage: TokenStorage,
    oAuthWorkerHost: String,
    authSpec: JobSpec = JobSpec(
        "Dummy Cloud Backup Auth",
        "backup-auth",
        headerText = "Use this to log in to our emulated cloud backup. It's really just a file server hosted elsewhere within the LightOS emulator.",
        buttonText = "Authorize"
    ),
    accountType: RemoteBackupProvider = RemoteBackupProvider.Custom,
    onLinked: suspend (accountType: RemoteBackupProvider) -> Unit = {},
): LeafView {
    val oAuthTunnelClient = EmulatorRelayOAuthTunnelClient(oAuthWorkerHost)
    return LeafView(
        authSpec, OAuthJobDataTree(
            accountType.name,
            oAuthTunnelClient,
            tokenStorage,
            onLinked = { onLinked(accountType) }
        )
    )
}
