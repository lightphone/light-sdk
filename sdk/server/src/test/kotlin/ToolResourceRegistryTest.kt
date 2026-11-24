import com.thelightphone.sdk.server.*
import com.thelightphone.sdk.server.toolmanager.authorizedToolDataView
import com.thelightphone.toolmanager.FileBrowserSpec
import com.thelightphone.toolmanager.LeafView
import com.thelightphone.toolmanager.datatree.CustomDataTree
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Paths
import kotlinx.coroutines.*
import kotlin.test.*

class ToolResourceRegistryTest {
    @Test fun `revocation runs every cleanup, retries failures and prevents reconnection`() {
        val registry = ToolResourceRegistry()
        val events = mutableListOf<String>()
        var fails = true
        registry.register("tool") { events += "first"; if (fails) error("failed") }
        registry.register("tool") { events += "second" }
        assertFails { registry.revoke("tool") }
        assertEquals(listOf("first", "second"), events)
        assertFailsWith<SecurityException> { registry.register("tool") {} }
        fails = false
        registry.revoke("tool")
        assertEquals(listOf("first", "second", "first"), events)
        registry.allow("tool")
        registry.register("tool") {}.close()
    }

    @Test fun `revocation stops buffered stream reads and writes and closes rejected resources`() {
        val registry = ToolResourceRegistry()
        val input = registry.trackInputStream("tool", ByteArrayInputStream(byteArrayOf(1, 2)))
        val output = registry.trackOutputStream("tool", ByteArrayOutputStream())
        assertEquals(1, input.read())
        output.write(1)
        registry.revoke("tool")
        assertFailsWith<IOException> { input.read() }
        assertFailsWith<IOException> { output.write(2) }
        var closed = false
        assertFailsWith<SecurityException> { registry.trackInputStream("tool", object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { closed = true }
        }) }
        assertTrue(closed)
    }

    @Test fun `revocation cancels an in-flight operation without cancelling unrelated host work`() = runBlocking {
        val registry = ToolResourceRegistry()
        val started = CompletableDeferred<Unit>()
        val task = async {
            runCatching { registry.withResource("tool") { started.complete(Unit); awaitCancellation() } }
        }
        started.await()
        registry.revoke("tool")
        assertTrue(task.await().exceptionOrNull() is CancellationException)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun `cached Tool Manager view cannot keep reading after authorization changes`() = runBlocking {
        var allowed = true
        val registry = ToolResourceRegistry()
        var reads = 0
        val view = LeafView(FileBrowserSpec("Files", "files"), object : CustomDataTree() {
            override suspend fun getBytes(filePath: java.nio.file.Path) =
                Result.success(java.io.InputStream.nullInputStream().also { reads++ })
        })
        val guarded = authorizedToolDataView(view, "tool", registry) { allowed } as LeafView
        val stream = guarded.provider.getBytes(Paths.get("file")).getOrThrow()
        allowed = false
        registry.revoke("tool")
        assertFailsWith<IOException> { stream.read() }
        assertTrue(guarded.provider.getBytes(Paths.get("file")).isFailure)
        assertEquals(1, reads)
    }

    @Test fun `revoked token never revives when eligibility returns or changes during validation`() {
        val tokens = SdkClientAuthorizations()
        val old = tokens.issue(42)
        assertTrue(tokens.validate(42, old) { true })
        assertFalse(tokens.validate(42, old) { tokens.revoke(42); true })
        assertFalse(tokens.validate(42, old) { true })
        val next = tokens.issue(42)
        assertNotEquals(old, next)
        assertFalse(tokens.validate(42, next) { false })
        assertFalse(tokens.validate(42, next) { true })
    }
}
