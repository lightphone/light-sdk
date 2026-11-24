package com.thelightphone.sdk.server

import com.thelightphone.sdk.trust.InstallationFingerprint
import java.util.UUID

data class ToolPackageState(val enabledState: Int, val fingerprint: InstallationFingerprint)

interface ToolRestrictionPlatform {
    fun state(packageName: String): ToolPackageState
    fun setEnabledState(packageName: String, enabledState: Int)
    fun stopProcesses(packageName: String)
    fun requestPurge(packageName: String)
}

class JournaledToolRestrictionBackend(
    private val platform: ToolRestrictionPlatform,
    private val store: ToolRestrictionStore,
    private val revoke: (String) -> Unit,
    private val cleanup: (String) -> Unit,
    private val allow: (String) -> Unit,
) : ToolRestrictionBackend {
    override fun ownedRestriction(packageName: String): OwnedToolRestriction? {
        val record = store.records().singleOrNull { it.packageName == packageName } ?: return null
        if (platform.state(packageName).enabledState != DISABLED) {
            store.remove(packageName)
            return null
        }
        return record
    }
    override fun revokeSdkAuthorization(packageName: String) = revoke(packageName)
    override fun allowSdkAuthorization(packageName: String) = allow(packageName)

    override fun cleanupHostResources(packageName: String) {
        val failures = mutableListOf<Exception>()
        try { platform.stopProcesses(packageName) } catch (error: Exception) { failures += error }
        try { cleanup(packageName) } catch (error: Exception) { failures += error }
        if (failures.isNotEmpty()) throw IllegalStateException("Resource/process cleanup failed", failures.first()).also {
            failures.drop(1).forEach(it::addSuppressed)
        }
    }

    override fun disable(packageName: String) {
        val state = platform.state(packageName)
        require(state.enabledState in 0..4) { "Unknown enabled state" }
        val existing = ownedRestriction(packageName)
        if (state.enabledState !in setOf(0, 1, 4)) {
            if (existing != null) {
                check(existing.applied && existing.installationFingerprint == state.fingerprint && state.enabledState == DISABLED) {
                    "Restriction ownership is uncertain; preserving disabled state"
                }
            }
            return
        }
        val pending = OwnedToolRestriction(packageName, state.enabledState, UUID.randomUUID().toString(), state.fingerprint)
        store.put(pending)
        check(platform.state(packageName) == state) { "Package changed before disabling" }
        platform.setEnabledState(packageName, DISABLED)
        check(platform.state(packageName) == state.copy(enabledState = DISABLED)) { "Disable did not take effect on the same installation" }
        store.put(pending.copy(applied = true))
    }

    override fun restrictionStillOwned(record: OwnedToolRestriction): Boolean {
        val state = platform.state(record.packageName)
        return record.applied && ownedRestriction(record.packageName) == record &&
            state.enabledState == DISABLED && state.fingerprint == record.installationFingerprint
    }

    override fun restore(record: OwnedToolRestriction) {
        check(restrictionStillOwned(record)) { "Restriction ownership changed" }
        platform.setEnabledState(record.packageName, record.priorEnabledState)
        val restored = platform.state(record.packageName)
        check(restored.enabledState == record.priorEnabledState && restored.fingerprint == record.installationFingerprint)
        store.remove(record.packageName)
    }

    override fun purge(packageName: String) = platform.requestPurge(packageName)

    companion object { const val DISABLED = 2 }
}
