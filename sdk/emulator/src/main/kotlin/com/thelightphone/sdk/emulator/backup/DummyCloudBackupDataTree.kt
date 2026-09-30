package com.thelightphone.sdk.emulator.backup

import com.thelightphone.backup.RemoteBackupProvider
import com.thelightphone.sdk.server.toolmanager.OAuthJobDataTree
import com.thelightphone.sdk.server.toolmanager.TokenStorage
import com.thelightphone.toolmanager.BranchView
import com.thelightphone.toolmanager.JobSpec
import com.thelightphone.toolmanager.LeafView
import com.thelightphone.toolmanager.RootViewSpec
import com.thelightphone.toolmanager.datatree.StaticBranchProvider

// tool manager branch for emulator backup options
// for now, emulator only supports its own custom cloud backup (which is just a local server)
fun dummyCloudBackupDataTree(
    tokenStorage: TokenStorage,
    oAuthWorkerHost: String,
    rootPath: String,
    authSpec: JobSpec = JobSpec(
        "Dummy Cloud Backup Auth",
        "backup-auth",
        headerText = "Use this to log in to our emulated cloud backup. It's really just a file server hosted elsewhere within the LightOS emulator.",
        buttonText = "Authorize"
    ),
    accountType: RemoteBackupProvider = RemoteBackupProvider.Custom,
    onLinked: suspend (accountType: RemoteBackupProvider) -> Unit = {},
): BranchView {
    val oAuthTunnelClient = EmulatorRelayOAuthTunnelClient(oAuthWorkerHost)
    val endpoint = LeafView(
        authSpec, OAuthJobDataTree(
            accountType.name,
            oAuthTunnelClient,
            tokenStorage,
            onLinked = { onLinked(accountType) }
        )
    )
    return BranchView(
        RootViewSpec("Backup", rootPath),
        StaticBranchProvider(listOf(endpoint))
    )
}