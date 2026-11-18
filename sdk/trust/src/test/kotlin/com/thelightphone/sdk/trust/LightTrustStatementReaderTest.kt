package com.thelightphone.sdk.trust

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class LightTrustStatementReaderTest {
    @Test fun `duplicate trust statements are rejected even when zip reader could select one`() {
        val file = Files.createTempFile("statement-", ".apk").toFile()
        try {
            val name = "META-INF/light-trust.json"
            val alternate = "META-INF/light-trust.jsox"
            ZipOutputStream(file.outputStream()).use { zip ->
                for (entry in listOf(name, alternate)) {
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write("{}".toByteArray())
                    zip.closeEntry()
                }
            }
            // Rename the equal-length second name in both local and central headers.
            val raw = file.readBytes()
            val needle = alternate.toByteArray()
            for (offset in 0..raw.size - needle.size) {
                if (needle.indices.all { raw[offset + it] == needle[it] }) {
                    name.toByteArray().copyInto(raw, offset)
                }
            }
            file.writeBytes(raw)
            assertEquals(StatementReadResult.Malformed, LightTrustStatementReader.read(file))
        } finally { file.delete() }
    }

    @Test fun `oversized compressed statement is bounded and absent statement is distinct`() {
        val file = Files.createTempFile("statement-", ".apk").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/light-trust.json"))
                zip.write(ByteArray(65_537))
                zip.closeEntry()
            }
            assertEquals(StatementReadResult.Malformed, LightTrustStatementReader.read(file))
            ZipOutputStream(file.outputStream()).use { }
            assertEquals(StatementReadResult.Absent, LightTrustStatementReader.read(file))
            assertEquals(64, apkSha256(file).length)
        } finally { file.delete() }
    }
}
