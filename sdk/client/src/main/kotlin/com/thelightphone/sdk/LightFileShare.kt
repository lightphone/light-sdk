package com.thelightphone.sdk

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.thelightphone.toolmanager.LightFileProvider
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Instant

/**
 * Tools for managing files in a "shared" directory that LightOS can access via a content provider
 */
class LightFileShare internal constructor(private val androidContext: Context) {
    data class LightFile(
        val name: String,
        val lastModified: Instant,
        val isFile: Boolean
    )

    private val root =
        File(androidContext.filesDir, LightFileProvider.SHARED_DIR).also { it.mkdirs() }

    fun <T> read(
        relativePath: String,
        charset: Charset = Charsets.UTF_8,
        block: (InputStreamReader) -> T
    ): T? {
        val file = resolve(relativePath)
        if (!file.isFile) return null
        return file.reader(charset).use(block)
    }

    fun delete(relativePath: String): Boolean {
        return resolve(relativePath).delete()
    }

    fun exists(relativePath: String): Boolean {
        return resolve(relativePath).exists()
    }

    fun list(relativePath: String = ""): List<LightFile> {
        val dir = if (relativePath.isEmpty()) root else resolve(relativePath)
        return dir.listFiles()?.map {
            LightFile(it.name, Instant.fromEpochMilliseconds(it.lastModified()), it.isFile)
        }.orEmpty()
    }

    fun getUri(relativePath: String): Uri {
        val authority = "${androidContext.packageName}.lightfileprovider"
        return "content://$authority/$relativePath".toUri()
    }

    fun write(relativePath: String, block: (OutputStreamWriter) -> Unit) {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        file.writer().use(block)
    }

    fun <T> readStream(relativePath: String, block: (InputStream) -> T): T? {
        val file = resolve(relativePath)
        if (!file.isFile) return null
        return file.inputStream().use(block)
    }

    fun writeBytes(relativePath: String, bytes: ByteArray) {
        writeStream(relativePath) { it.write(bytes) }
    }

    /**
     * Writes raw bytes to the file by streaming them through [block], replacing any existing
     * content. Use this (rather than [write]) for binary data.
     *
     * The data is written to a temporary file first and only moved into place once [block]
     * returns, so a reader (such as a backup) never sees a half-written file, and a failure
     * partway through leaves any existing file untouched.
     */
    fun writeStream(relativePath: String, block: (OutputStream) -> Unit) {
        val file = resolve(relativePath)
        val dir = file.parentFile
        dir?.mkdirs()
        // dot-prefixed so backups skip it (hidden files aren't backed up by default)
        val temp = File.createTempFile(".${file.name}.", ".tmp", dir)
        try {
            temp.outputStream().use(block)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    private fun resolve(relativePath: String): File {
        val file = File(root, relativePath).canonicalFile
        if (!file.path.startsWith(root.canonicalPath)) {
            throw SecurityException("Path traversal not allowed")
        }
        return file
    }
}
