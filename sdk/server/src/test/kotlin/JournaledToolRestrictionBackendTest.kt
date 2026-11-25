import com.thelightphone.sdk.server.*
import com.thelightphone.sdk.trust.*
import java.nio.file.Files
import kotlin.test.*

class JournaledToolRestrictionBackendTest {
    private val fingerprint = InstallationFingerprint("/data/app/tool/base.apk", 1, 2)

    private class Store : ToolRestrictionStore {
        val values = linkedMapOf<String, OwnedToolRestriction>()
        var failWrite: Int? = null
        var writes = 0

        override fun records() = values.values.toList()

        override fun put(record: OwnedToolRestriction) {
            writes++
            if (writes == failWrite) error("journal write failed")
            values[record.packageName] = record
        }

        override fun remove(packageName: String) {
            values.remove(packageName)
        }
    }

    private inner class Platform : ToolRestrictionPlatform {
        var snapshot = ToolPackageState(0, fingerprint)
        val operations = mutableListOf<String>()
        var failDisable = false

        override fun state(packageName: String) = snapshot

        override fun setEnabledState(packageName: String, enabledState: Int) {
            operations += "state:$enabledState"
            if (failDisable) error("platform refused")
            snapshot = snapshot.copy(enabledState = enabledState)
        }

        override fun stopProcesses(packageName: String) {
            operations += "stop"
        }

        override fun requestPurge(packageName: String): String {
            operations += "purge"
            return "attempt"
        }
    }

    private fun backend(platform: Platform, store: ToolRestrictionStore) =
        JournaledToolRestrictionBackend(platform, store, {}, {}, {})

    @Test
    fun `applied ownership survives reopen and restores the actual prior state`() {
        for (prior in listOf(0, 1, 4)) {
            val directory = Files.createTempDirectory("restriction-journal-")
            val path = directory.resolve("restrictions")
            try {
                val platform = Platform().apply { snapshot = snapshot.copy(enabledState = prior) }
                val first = backend(platform, FileToolRestrictionStore(path))
                first.disable("tool")
                assertEquals(2, platform.snapshot.enabledState)
                val reopened = backend(platform, FileToolRestrictionStore(path))
                val owned = assertNotNull(reopened.ownedRestriction("tool"))
                assertEquals(prior, owned.priorEnabledState)
                assertTrue(owned.applied)
                assertTrue(reopened.restrictionStillOwned(owned))
                reopened.restore(owned)
                assertEquals(prior, platform.snapshot.enabledState)
                assertTrue(FileToolRestrictionStore(path).records().isEmpty())
            } finally {
                Files.deleteIfExists(path)
                Files.deleteIfExists(directory)
            }
        }
    }

    @Test
    fun `journal intent is durable before disabling and uncertain completion cannot restore`() {
        val platform = Platform()
        val store = Store().apply { failWrite = 1 }
        assertFails { backend(platform, store).disable("tool") }
        assertTrue(platform.operations.isEmpty())
        store.failWrite = 3
        assertFails { backend(platform, store).disable("tool") }
        assertEquals(2, platform.snapshot.enabledState)
        val pending = store.records().single()
        assertFalse(pending.applied)
        assertFalse(backend(platform, store).restrictionStillOwned(pending))
        assertFails { backend(platform, store).restore(pending) }
        assertFails { backend(platform, store).disable("tool") }
        assertEquals(listOf("state:2"), platform.operations)
    }

    @Test
    fun `preexisting restrictions are not adopted and later user state or reinstall is not overridden`() {
        for (prior in listOf(2, 3)) {
            val platform = Platform().apply { snapshot = snapshot.copy(enabledState = prior) }
            val store = Store()
            backend(platform, store).disable("tool")
            assertTrue(store.records().isEmpty())
            assertTrue(platform.operations.isEmpty())
        }
        val platform = Platform()
        val store = Store()
        val backend = backend(platform, store)
        backend.disable("tool")
        val owned = store.records().single()
        platform.snapshot = platform.snapshot.copy(enabledState = 3)
        assertFalse(backend.restrictionStillOwned(owned))
        assertFails { backend.restore(owned) }
        platform.snapshot =
            platform.snapshot.copy(
                enabledState = 2,
                fingerprint = fingerprint.copy(baseApkPath = "/data/app/new/base.apk"),
            )
        assertFalse(backend.restrictionStillOwned(owned))
        assertFails { backend.restore(owned) }
        assertEquals(listOf("state:2"), platform.operations)
    }

    @Test
    fun `corrupt journal cannot become an empty authority`() {
        val directory = Files.createTempDirectory("restriction-corrupt-")
        val path = directory.resolve("records")
        try {
            val record = OwnedToolRestriction("tool", 0, "owner", fingerprint, true)
            FileToolRestrictionStore(path).put(record)
            val bytes = Files.readAllBytes(path)
            bytes[40] = (bytes[40].toInt() xor 1).toByte()
            Files.write(path, bytes)
            assertFails { FileToolRestrictionStore(path) }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `later user disabled state is preserved when policy allows again`() {
        val platform = Platform()
        val store = Store()
        val coordinator = ToolEnforcementCoordinator(backend(platform, store))
        coordinator.reconcileAll(listOf("tool" to ToolTrustRuntime.unavailable()))
        platform.snapshot = platform.snapshot.copy(enabledState = 3)
        val allowed =
            ToolTrustRuntime.unavailable()
                .copy(outcome = ToolEvaluationOutcome.PolicyDecision(LightInstallDecision.Allow))
        assertEquals(ToolContainment.Allowed, coordinator.reconcile("tool", allowed).containment)
        assertEquals(3, platform.snapshot.enabledState)
        assertTrue(store.records().isEmpty())
        assertEquals(listOf("stop", "state:2"), platform.operations)
    }

    @Test
    fun `denial cleanup attempts host and process cleanup even when one fails`() {
        val platform = Platform()
        var revoked = false
        val backend =
            JournaledToolRestrictionBackend(
                platform,
                Store(),
                { revoked = true },
                { error("host callback failed") },
                {},
            )
        val result =
            ToolEnforcementCoordinator(backend).reconcile("tool", ToolTrustRuntime.unavailable())
        assertTrue(revoked)
        assertEquals(listOf("stop", "state:2"), platform.operations)
        assertEquals(ToolContainment.Failed, result.containment)
    }
}
