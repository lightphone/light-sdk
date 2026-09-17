package com.thelightphone.sdk.emulator.backup

import android.content.Context
import com.thelightphone.sdk.server.toolmanager.OAuthJobDataTree
import com.thelightphone.sdk.server.toolmanager.TokenStorage
import com.thelightphone.toolmanager.BranchView
import com.thelightphone.toolmanager.JobSpec
import com.thelightphone.toolmanager.LeafView
import com.thelightphone.toolmanager.RootViewSpec
import com.thelightphone.toolmanager.datatree.StaticBranchProvider

fun dummyCloudBackupDataTree(
    context: Context,
    tokenStorage: TokenStorage,
    oAuthWorkerHost: String,
    authSpec: JobSpec = JobSpec(
        "Dummy Cloud Backup Auth",
        "backup-auth",
        headerText = "Use this to log in to our emulated cloud backup. It's really just a file server hosted elsewhere within the LightOS emulator.",
        buttonText = "Authorize"
    )
): BranchView {
    val oAuthTunnelClient = EmulatorRelayOAuthTunnelClient(oAuthWorkerHost)
    val endpoint = LeafView(
        authSpec, OAuthJobDataTree(
            "Custom",
            oAuthTunnelClient,
            tokenStorage
        )
    )
    return BranchView(
        RootViewSpec("Backup", "backup"),
        StaticBranchProvider(listOf(endpoint))
    )
}