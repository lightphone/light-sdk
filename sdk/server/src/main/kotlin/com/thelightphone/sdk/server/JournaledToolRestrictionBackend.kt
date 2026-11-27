package com.thelightphone.sdk.server

import com.thelightphone.sdk.trust.InstallationFingerprint
import java.util.UUID

data class ToolPackageState(
    val enabledState: PackageEnabledSetting,
    val fingerprint: InstallationFingerprint,
)

interface ToolRestrictionPlatform {
    fun state(packageName: String): ToolPackageState

    fun setEnabledState(packageName: String, enabledState: PackageEnabledSetting)

    fun stopProcesses(packageName: String)

    fun requestPurge(packageName: String): String
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
        if (platform.state(packageName).enabledState != PackageEnabledSetting.Disabled) {
            store.remove(packageName)
            return null
        }
        return record
    }

    override fun revokeSdkAuthorization(packageName: String) = revoke(packageName)

    override fun allowSdkAuthorization(packageName: String) = allow(packageName)

    override fun cleanupHostResources(packageName: String) {
        val failures = mutableListOf<Exception>()
        try {
            platform.stopProcesses(packageName)
        } catch (error: Exception) {
            failures += error
        }
        try {
            cleanup(packageName)
        } catch (error: Exception) {
            failures += error
        }
        if (failures.isNotEmpty())
            throw IllegalStateException("Resource/process cleanup failed", failures.first()).also {
                failures.drop(1).forEach(it::addSuppressed)
            }
    }

    override fun disable(packageName: String) {
        val state = platform.state(packageName)
        val existing = ownedRestriction(packageName)
        if (!state.enabledState.canBeRestricted()) {
            if (existing != null) {
                check(
                    existing.applied &&
                        existing.installationFingerprint == state.fingerprint &&
                        state.enabledState == PackageEnabledSetting.Disabled,
                ) {
                    "Restriction ownership is uncertain; preserving disabled state"
                }
            }
            return
        }
        val pending =
            OwnedToolRestriction(
                packageName,
                state.enabledState,
                UUID.randomUUID().toString(),
                state.fingerprint,
            )
        store.put(pending)
        check(platform.state(packageName) == state) { "Package changed before disabling" }
        platform.setEnabledState(packageName, PackageEnabledSetting.Disabled)
        check(
            platform.state(packageName) ==
                state.copy(enabledState = PackageEnabledSetting.Disabled),
        ) {
            "Disable did not take effect on the same installation"
        }
        store.put(pending.copy(applied = true))
    }

    override fun restrictionStillOwned(record: OwnedToolRestriction): Boolean {
        val state = platform.state(record.packageName)
        return record.applied &&
            ownedRestriction(record.packageName) == record &&
            state.enabledState == PackageEnabledSetting.Disabled &&
            state.fingerprint == record.installationFingerprint
    }

    override fun restore(record: OwnedToolRestriction) {
        check(restrictionStillOwned(record)) { "Restriction ownership changed" }
        platform.setEnabledState(record.packageName, record.priorEnabledState)
        val restored = platform.state(record.packageName)
        check(
            restored.enabledState == record.priorEnabledState &&
                restored.fingerprint == record.installationFingerprint,
        )
        store.remove(record.packageName)
    }

    override fun purge(packageName: String) = platform.requestPurge(packageName)
}
