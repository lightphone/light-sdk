package com.thelightphone.sdk.trust

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

sealed interface StatementReadResult {
    data object Absent : StatementReadResult
    data object Malformed : StatementReadResult
    data class Present(val statement: LightTrustStatement) : StatementReadResult
}

object LightTrustStatementReader {
    private const val ENTRY = "META-INF/light-trust.json"
    private const val MAX_BYTES = 64 * 1024

    fun read(apk: File): StatementReadResult = try {
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().filter { it.name == ENTRY }.toList()
            when {
                entries.isEmpty() -> StatementReadResult.Absent
                entries.size != 1 || entries.single().isDirectory -> StatementReadResult.Malformed
                else -> {
                    val bytes = zip.getInputStream(entries.single()).use { input ->
                        val buffer = ByteArray(MAX_BYTES + 1)
                        var count = 0
                        while (count < buffer.size) {
                            val read = input.read(buffer, count, buffer.size - count)
                            if (read == -1) break
                            count += read
                        }
                        buffer.copyOf(count)
                    }
                    if (bytes.size > MAX_BYTES) StatementReadResult.Malformed
                    else when (val parsed = LightTrustStatementParser.parse(bytes)) {
                        is TrustResult.Success -> StatementReadResult.Present(parsed.value)
                        is TrustResult.Failure -> StatementReadResult.Malformed
                    }
                }
            }
        }
    } catch (_: Exception) {
        StatementReadResult.Malformed
    }
}

fun apkSha256(apk: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    apk.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
