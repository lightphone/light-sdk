package com.thelightphone.sdk.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.thelightphone.sdk.server.LightSdkServer.queryInstalledClients
import com.thelightphone.sdk.trust.InstallationFingerprint
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ToolEnforcementController(
    context: Context,
    private val store: ToolRestrictionStore,
    platform: ToolRestrictionPlatform = AndroidToolRestrictionPlatform(context),
    private val onReconciled: () -> Unit = {},
) {
    private val context = context.applicationContext
    private val pending = AtomicBoolean(false)
    private val status = ToolEnforcementStatus()
    val snapshot = status.snapshot
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "tool-enforcement").apply { isDaemon = true } }
    @Volatile private var lastUids: Map<String, Int> = emptyMap()

    private val backend = JournaledToolRestrictionBackend(platform, store,
        revoke = { packageName ->
            val uid = this.context.packageManager.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS).uid
            LightSdkServer.clientAuthorizations.revoke(uid)
        },
        cleanup = { packageName ->
            val failures = mutableListOf<Exception>()
            fun attempt(action: () -> Unit) { try { action() } catch (error: Exception) { failures += error } }
            attempt { LightSdkServer.toolResources.revoke(packageName) }
            attempt { runBlocking { LightPushRegistry.removePackage(this@ToolEnforcementController.context, packageName) } }
            attempt { LightSdkServer.onRevokeToolResources(this.context, packageName) }
            if (failures.isNotEmpty()) throw IllegalStateException("Host cleanup incomplete", failures.first()).also {
                failures.drop(1).forEach(it::addSuppressed)
            }
        },
        allow = { LightSdkServer.toolResources.allow(it) },
    )
    private val coordinator = ToolEnforcementCoordinator(backend)

    private fun registerPackageEvents() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        val receiver = ToolPackageChangeReceiver()
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    fun permitsAccess(packageName: String): Boolean {
        val observed = snapshot.value
        return !observed.checking && !observed.failed && coordinator.permitsAccess(packageName) &&
            snapshot.value.revision == observed.revision
    }

    fun requestReconciliation() {
        status.request()
        pending.set(true)
        executor.execute {
            while (pending.getAndSet(false)) {
                val revision = snapshot.value.revision
                try {
                    val clients = context.queryInstalledClients()
                    lastUids = lastUids + clients.mapNotNull { client ->
                        client.packageInfo.applicationInfo?.uid?.let { client.packageInfo.packageName to it }
                    }.toMap()
                    val installed = clients.map { it.packageInfo.packageName }.toMutableSet()
                    for (record in store.records()) {
                        try {
                            context.packageManager.getApplicationInfo(record.packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
                            installed += record.packageName
                        } catch (_: PackageManager.NameNotFoundException) { store.remove(record.packageName) }
                    }
                    val level = LightSdkServer.provideSdkSettings(context).clientFilterLevel
                    val evaluations = installed.filter { it != context.packageName }.map {
                        it to LightSdkServer.evaluateTool(level, context, it)
                    }
                    val results = coordinator.reconcileAll(evaluations)
                    status.complete(revision, results)
                    for (result in results.filter { it.containment == ToolContainment.Failed || it.containment == ToolContainment.OwnershipConflict }) {
                        LightSdkServer.reportError("ToolEnforcement", null,
                            "${result.packageName}: ${result.containment}: ${result.failures.joinToString()}")
                    }
                    if (revision == snapshot.value.revision) onReconciled()
                } catch (error: Exception) {
                    coordinator.invalidate()
                    status.fail(revision)
                    LightSdkServer.reportError("ToolEnforcement", error, "Reconciliation failed; access remains restricted")
                }
            }
        }
    }

    fun onPackageRemoved(packageName: String, replacing: Boolean) {
        executor.execute {
            lastUids[packageName]?.let { uid ->
                LightSdkServer.clientAuthorizations.revoke(uid)
                try { LightSdkServer.onRevokeToolUid(uid) } catch (error: Exception) {
                    LightSdkServer.reportError("ToolEnforcement", error, "Removed UID cleanup failed")
                }
            }
            try { LightSdkServer.toolResources.revoke(packageName) } catch (error: Exception) {
                LightSdkServer.reportError("ToolEnforcement", error, "Removed package resource cleanup failed")
            }
            try { runBlocking { LightPushRegistry.removePackage(context, packageName) } } catch (error: Exception) {
                LightSdkServer.reportError("ToolEnforcement", error, "Removed package push cleanup failed")
            }
            if (replacing) return@execute
            try {
                val fingerprint = try {
                    val info = context.packageManager.getPackageInfo(packageName, 0)
                    InstallationFingerprint(requireNotNull(info.applicationInfo).sourceDir, info.firstInstallTime, info.lastUpdateTime)
                } catch (_: PackageManager.NameNotFoundException) { null }
                val owned = store.records().singleOrNull { it.packageName == packageName }
                if (fingerprint == null || owned?.installationFingerprint != fingerprint) store.remove(packageName)
            } catch (error: Exception) {
                LightSdkServer.reportError("ToolEnforcement", error, "Restriction journal removal failed")
            }
        }
        requestReconciliation()
    }

    internal fun onPurgeOutcome(packageName: String, attemptId: String, outcome: ToolPurgeOutcome) {
        executor.execute {
            status.purgeFinished(packageName, attemptId, outcome)
        }
    }

    companion object {
        fun start(context: Context, onReconciled: () -> Unit = {}): ToolEnforcementController? {
            LightSdkServer.toolEnforcement?.let { it.requestReconciliation(); return it }
            LightSdkServer.toolEnforcementRequired = true
            return try {
                ToolEnforcementController(context, FileToolRestrictionStore(File(context.filesDir, "trust/restrictions.bin").toPath()),
                    onReconciled = onReconciled).also {
                    it.registerPackageEvents()
                    LightSdkServer.toolEnforcement = it
                    it.requestReconciliation()
                }
            } catch (error: Exception) {
                LightSdkServer.reportError("ToolEnforcement", error, "Restriction storage unavailable; preserve apps/data")
                null
            }
        }
    }
}
