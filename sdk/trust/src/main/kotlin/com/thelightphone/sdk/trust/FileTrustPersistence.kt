package com.thelightphone.sdk.trust

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

private const val MAX_BUNDLE_BYTES = 100_000_000
private const val MAX_SIGNATURE_BYTES = 100_000

/**
 * Dedicated private file; never point this backend at an admission or stamp cache file. Missing
 * records return null. Read failures, including corrupt records, propagate to the caller.
 */
class FileTrustPersistence(private val path: Path) : TrustPersistence {
    override fun read(): SignedTrustBundle? {
        val bytes =
            try {
                Files.readAllBytes(path)
            } catch (_: java.nio.file.NoSuchFileException) {
                return null
            }
        require(bytes.size >= 40) { "Truncated trust bundle data" }
        val payload = bytes.copyOfRange(32, bytes.size)
        require(
            MessageDigest.isEqual(
                bytes.copyOfRange(0, 32),
                MessageDigest.getInstance("SHA-256").digest(payload),
            )
        ) {
            "Corrupt trust bundle data"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == 0x4c414433) { "Unsupported trust bundle format" }
            val bundleBytes = ByteArray(input.readInt().also { require(it in 0..MAX_BUNDLE_BYTES) })
            input.readFully(bundleBytes)
            val signature =
                ByteArray(input.readInt().also { require(it in 0..MAX_SIGNATURE_BYTES) })
            input.readFully(signature)
            require(input.read() == -1) { "Trailing trust bundle data" }
            SignedTrustBundle(bundleBytes, signature)
        }
    }

    override fun write(bundle: SignedTrustBundle) {
        val bundleBytes = bundle.bytes
        require(bundleBytes.size <= MAX_BUNDLE_BYTES) { "Trust bundle exceeds the storage limit" }
        val signature = bundle.signature
        require(signature.size <= MAX_SIGNATURE_BYTES) {
            "Trust signature exceeds the storage limit"
        }

        val bytes =
            ByteArrayOutputStream()
                .also { buffer ->
                    DataOutputStream(buffer).use { output ->
                        output.writeInt(0x4c414433)
                        output.writeInt(bundleBytes.size)
                        output.write(bundleBytes)
                        output.writeInt(signature.size)
                        output.write(signature)
                    }
                }
                .toByteArray()
                .let { payload -> MessageDigest.getInstance("SHA-256").digest(payload) + payload }
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".trust-bundle-", ".tmp")
        var replaced = false
        try {
            FileChannel.open(temporary, WRITE).use { file ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) file.write(buffer)
                file.force(true)
            }
            // Do not fall back to a non-atomic replacement on unsupported filesystems.
            FileChannel.open(parent, READ).use { directory ->
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                replaced = true
                directory.force(true)
            }
        } catch (error: Exception) {
            if (replaced) throw TrustWriteDurabilityUnknown(error)
            throw error
        } finally {
            if (!replaced) Files.deleteIfExists(temporary)
        }
    }
}
