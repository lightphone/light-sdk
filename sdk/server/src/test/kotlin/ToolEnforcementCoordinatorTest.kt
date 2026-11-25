import com.thelightphone.sdk.server.*
import com.thelightphone.sdk.trust.*
import kotlin.test.*

class ToolEnforcementCoordinatorTest {
    private val denied = ToolTrustRuntime.unavailable(DenyReason.TrustUnavailable)
    private val allowed = ToolTrustRuntime.exceptionWithoutArtifact(AdmissionException.AllowAll)
    private class Backend : ToolRestrictionBackend {
        val events = mutableListOf<String>()
        var owned: OwnedToolRestriction? = null
        var ownerChanged = false
        var failCleanup = false
        var failDisable = false
        override fun ownedRestriction(packageName: String) = owned
        override fun revokeSdkAuthorization(packageName: String) { events += "revoke" }
        override fun cleanupHostResources(packageName: String) {
            events += "cleanup"
            if (failCleanup) error("active session cleanup failed")
        }
        override fun disable(packageName: String) {
            events += "disable"
            if (failDisable) error("permission denied")
            if (owned == null) owned = OwnedToolRestriction(packageName, 0, "owner")
        }
        override fun restrictionStillOwned(record: OwnedToolRestriction) = !ownerChanged && owned == record
        override fun restore(record: OwnedToolRestriction) { events += "restore"; owned = null }
        override fun purge(packageName: String): String { events += "purge"; return "attempt" }
    }

    @Test fun `all denials revoke and clean before reversible disabling`() {
        val backend = Backend()
        val coordinator = ToolEnforcementCoordinator(backend)
        assertFalse(coordinator.permitsAccess("tool"))
        assertFalse(coordinator.permitsAccess("new-unevaluated-package"))
        val result = coordinator.reconcileAll(listOf("tool" to denied)).single()
        assertEquals(listOf("revoke", "cleanup", "disable"), backend.events)
        assertEquals(ToolContainment.Disabled, result.containment)
        assertFalse(coordinator.permitsAccess("tool"))
        assertFalse(coordinator.permitsAccess("new-unevaluated-package"))
    }

    @Test fun `bypass releases only owned reversible restriction`() {
        val backend = Backend()
        val coordinator = ToolEnforcementCoordinator(backend)
        coordinator.reconcileAll(listOf("tool" to denied))
        assertEquals(ToolContainment.Allowed, coordinator.reconcile("tool", allowed).containment)
        assertEquals("restore", backend.events.last())
        assertTrue(coordinator.permitsAccess("tool"))
        backend.ownerChanged = true
        coordinator.reconcile("tool", denied)
        assertEquals(ToolContainment.OwnershipConflict, coordinator.reconcile("tool", allowed).containment)
        assertFalse(coordinator.permitsAccess("tool"))
    }

    @Test fun `purge requires matching accepted block and reports request rather than completed uninstall`() {
        val kill = LightInstallDecision.Kill(BlockAction.Purge, "withdrawn")
        for (version in listOf(null, 7L)) {
            val backend = Backend()
            val coordinator = ToolEnforcementCoordinator(backend)
            val evaluation = denied.copy(outcome = ToolEvaluationOutcome.PolicyDecision(kill), bundleVersion = version)
            val result = coordinator.reconcile("tool", evaluation)
            assertEquals(if (version == null) ToolContainment.Failed else ToolContainment.PurgeRequested, result.containment)
            assertEquals(if (version == null) "disable" else "purge", backend.events.last())
            assertTrue("disable" in backend.events)
            assertFalse(coordinator.permitsAccess("tool"))
        }
    }

    @Test fun `cleanup failure does not skip disabling or pretend containment succeeded`() {
        val backend = Backend().apply { failCleanup = true }
        val result = ToolEnforcementCoordinator(backend).reconcile("tool", denied)
        assertEquals(listOf("revoke", "cleanup", "disable"), backend.events)
        assertEquals(ToolContainment.Failed, result.containment)
        assertTrue(result.failures.single().contains("cleanup"))
        backend.failCleanup = false
        backend.failDisable = true
        assertEquals(ToolContainment.Failed, ToolEnforcementCoordinator(backend).reconcile("tool", denied).containment)
    }
}
