import com.thelightphone.sdk.server.*
import com.thelightphone.sdk.trust.*
import kotlin.test.*

class ToolEnforcementStatusTest {
    private fun result(containment: ToolContainment = ToolContainment.Disabled) = ToolEnforcementResult(
        "tool", ToolTrustRuntime.unavailable().copy(bundleVersion = 4), containment)

    @Test fun `new requests immediately hide old results and ignore stale completion and failure`() {
        val status = ToolEnforcementStatus()
        val first = status.request()
        status.complete(first, listOf(result()))
        assertFalse(status.snapshot.value.checking)
        assertEquals(1, status.snapshot.value.results.size)
        val second = status.request()
        assertTrue(status.snapshot.value.checking)
        assertTrue(status.snapshot.value.results.isEmpty())
        status.complete(first, listOf(result()))
        status.fail(first)
        assertTrue(status.snapshot.value.checking)
        status.fail(second)
        assertTrue(status.snapshot.value.failed)
        assertFalse(status.snapshot.value.checking)
        assertTrue(status.snapshot.value.results.isEmpty())
    }

    @Test fun `purge failure updates only matching current pending removal`() {
        val status = ToolEnforcementStatus()
        status.complete(status.request(), listOf(result(ToolContainment.PurgeRequested)))
        status.purgeFailed("tool", 3, 1)
        assertEquals(ToolContainment.PurgeRequested, status.snapshot.value.results.single().containment)
        status.purgeFailed("tool", 4, 1)
        assertEquals(ToolContainment.Failed, status.snapshot.value.results.single().containment)
        status.request()
        status.purgeFailed("tool", 4, 1)
        assertTrue(status.snapshot.value.results.isEmpty())
    }

    @Test fun `policy text remains visible but failure never claims successful disable or removal`() {
        val result = result(ToolContainment.Failed).copy(evaluation = ToolTrustRuntime.unavailable().copy(
            outcome = ToolEvaluationOutcome.PolicyDecision(LightInstallDecision.Kill(BlockAction.Purge, "Unsafe tool"))))
        val description = result.describeStatus()
        assertEquals("Needs attention", description.state)
        assertEquals("Unsafe tool", description.reason)
        assertNotNull(description.attention)
        assertFalse(description.reason.contains("null"))
    }

    @Test fun `every denial has a readable reason and ownership conflicts explain restoration refusal`() {
        for (reason in DenyReason.entries) {
            val description = result().copy(evaluation = ToolTrustRuntime.unavailable().copy(
                outcome = ToolEvaluationOutcome.PolicyDecision(LightInstallDecision.Deny(reason)))).describeStatus()
            assertTrue(description.reason.isNotBlank())
            assertEquals("Disabled", description.state)
        }
        assertTrue(result(ToolContainment.OwnershipConflict).describeStatus().attention!!.contains("restored"))
    }
}
