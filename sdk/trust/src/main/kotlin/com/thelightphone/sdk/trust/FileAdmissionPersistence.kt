package com.thelightphone.sdk.trust

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.security.MessageDigest

/** Dedicated private file; never point this backend at a stamp cache or bundle file. */
class FileAdmissionPersistence(private val path: Path) : AdmissionPersistence {
    override fun read(): List<AdmissionRecord> {
        val bytes = try { Files.readAllBytes(path) } catch (_: java.nio.file.NoSuchFileException) {
            return emptyList()
        }
        require(bytes.size >= 40) { "Truncated admission data" }
        val payload = bytes.copyOfRange(32, bytes.size)
        require(MessageDigest.isEqual(bytes.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(payload))) {
            "Corrupt admission data"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val format = input.readInt()
            require(format == 0x4c414431 || format == 0x4c414432) { "Unsupported admission format" }
            val count = input.readInt()
            require(count in 0..100_000)
            val records = List(count) {
                AdmissionRecord(
                    input.readUTF(), input.readUTF(), input.readUTF(),
                    if (input.readBoolean()) input.readUTF() else null,
                    input.readLong(), if (input.readBoolean()) input.readLong() else null,
                    LightTrustFilterLevel.valueOf(input.readUTF()),
                    Instant.ofEpochSecond(input.readLong(), input.readInt().toLong()),
                    if (input.readBoolean()) AdmissionException.valueOf(input.readUTF()) else null,
                    if (format == 0x4c414432 && input.readBoolean())
                        InstallationFingerprint(input.readUTF(), input.readLong(), input.readLong()) else null,
                )
            }
            require(input.read() == -1) { "Trailing admission data" }
            require(records.map { it.packageName }.distinct().size == records.size)
            records
        }
    }

    override fun write(records: List<AdmissionRecord>) {
        require(records.size <= 100_000)
        require(records.map { it.packageName }.distinct().size == records.size)
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(0x4c414432)
                output.writeInt(records.size)
                for (record in records.sortedBy { it.packageName }) {
                    output.writeUTF(record.packageName)
                    output.writeUTF(record.apkSha256)
                    output.writeUTF(record.signerSha256)
                    output.writeBoolean(record.toolId != null)
                    record.toolId?.let(output::writeUTF)
                    output.writeLong(record.versionCode)
                    output.writeBoolean(record.admittedUnderBundleVersion != null)
                    record.admittedUnderBundleVersion?.let(output::writeLong)
                    output.writeUTF(record.admittedUnderLevel.name)
                    output.writeLong(record.admittedAt.epochSecond)
                    output.writeInt(record.admittedAt.nano)
                    output.writeBoolean(record.exception != null)
                    record.exception?.let { output.writeUTF(it.name) }
                    output.writeBoolean(record.installationFingerprint != null)
                    record.installationFingerprint?.let {
                        output.writeUTF(it.baseApkPath)
                        output.writeLong(it.firstInstallTimeMillis)
                        output.writeLong(it.lastUpdateTimeMillis)
                    }
                }
            }
        }.toByteArray().let { payload -> MessageDigest.getInstance("SHA-256").digest(payload) + payload }
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".admission-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { file ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) file.write(buffer)
                file.force(true)
            }
            // Do not fall back to a non-atomic replacement on unsupported filesystems.
            FileChannel.open(parent, READ).use { directory ->
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                directory.force(true)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
