import com.thelightphone.sdk.server.installer.installFromPrivateCopy
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StagedApkInstallTest {
    @Test
    fun `replacing inbox apk after check cannot replace installed bytes`() {
        val root = Files.createTempDirectory("staged-apk-test").toFile()
        try {
            val inbox = root.resolve("inbox").apply { mkdir() }
            val privateDir = root.resolve("private").apply { mkdir() }
            val apk = inbox.resolve("tool.apk").apply { writeText("approved") }
            val replacement = inbox.resolve("replacement.apk").apply { writeText("untrusted") }
            var installed: ByteArray? = null

            val allowed = installFromPrivateCopy(apk, privateDir, isAllowed = { checked ->
                assertEquals("approved", checked.readText())
                Files.move(replacement.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            }, install = { staged -> installed = staged.readBytes() })

            assertTrue(allowed)
            assertEquals("untrusted", apk.readText())
            assertContentEquals("approved".toByteArray(), installed)
            assertTrue(privateDir.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `denied apk is not installed and staged copy is removed`() {
        val root = Files.createTempDirectory("staged-apk-test").toFile()
        try {
            val apk = root.resolve("tool.apk").apply { writeText("rejected") }
            val privateDir = root.resolve("private").apply { mkdir() }
            var installed = false
            assertFalse(installFromPrivateCopy(apk, privateDir, { false }, { installed = true }))
            assertFalse(installed)
            assertTrue(privateDir.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed install removes staged copy and propagates error`() {
        val root = Files.createTempDirectory("staged-apk-test").toFile()
        try {
            val apk = root.resolve("tool.apk").apply { writeText("approved") }
            val privateDir = root.resolve("private").apply { mkdir() }
            assertFailsWith<IllegalStateException> {
                installFromPrivateCopy(apk, privateDir, { true }, { error("install failed") })
            }
            assertTrue(privateDir.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
