import com.thelightphone.sdk.server.PushRegistrationWrites
import com.thelightphone.sdk.server.ToolResourceRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PushRegistrationWritesTest {
    @Test fun `revocation cancels registration during endpoint lookup`() = runBlocking {
        val resources = ToolResourceRegistry()
        val writes = PushRegistrationWrites(resources)
        val lookupStarted = CompletableDeferred<Unit>()
        val finishLookup = CompletableDeferred<Unit>()
        val rows = mutableListOf("old")
        val registration = launch {
            resources.withResource("tool") {
                withContext(NonCancellable) {
                    lookupStarted.complete(Unit)
                    finishLookup.await()
                }
                writes.register("tool", { true }) { rows += "new" }
            }
        }

        lookupStarted.await()
        resources.revoke("tool")
        writes.remove { rows.clear() }
        finishLookup.complete(Unit)
        registration.join()

        assertTrue(registration.isCancelled)
        assertTrue(rows.isEmpty())
    }

    @Test fun `cleanup waits for an already started write and deletes its row`() = runBlocking {
        val writes = PushRegistrationWrites(ToolResourceRegistry())
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val registration = launch {
            writes.register("tool", { true }) {
                started.complete(Unit)
                finish.await()
                events += "write"
            }
        }
        started.await()
        registration.cancel()
        val cleanup = async { writes.remove { events += "delete" } }
        finish.complete(Unit)
        cleanup.await()
        registration.join()
        assertEquals(listOf("write", "delete"), events)

        assertFailsWith<SecurityException> {
            writes.register("tool", { false }) { events += "unexpected write" }
        }
        assertEquals(listOf("write", "delete"), events)
    }
}
