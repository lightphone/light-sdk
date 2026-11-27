package com.thelightphone.sdk.server

import com.thelightphone.sdk.trust.InstallationFingerprint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

interface ToolRestrictionStore {
    fun records(): List<OwnedToolRestriction>

    fun put(record: OwnedToolRestriction)

    fun remove(packageName: String)
}

class FileToolRestrictionStore(private val path: Path) : ToolRestrictionStore {
    private var available = true
    private var current = read().associateBy { it.packageName }

    @Synchronized
    override fun records(): List<OwnedToolRestriction> {
        check(available) { "Restriction journal unavailable" }
        return current.values.toList()
    }

    @Synchronized
    override fun put(record: OwnedToolRestriction) =
        replace(current + (record.packageName to record))

    @Synchronized
    override fun remove(packageName: String) {
        check(available) { "Restriction journal unavailable" }
        if (packageName in current) replace(current - packageName)
    }

    private fun read(): List<OwnedToolRestriction> {
        val raw =
            try {
                Files.readAllBytes(path)
            } catch (_: java.nio.file.NoSuchFileException) {
                return emptyList()
            }
        require(raw.size >= 40)
        val payload = raw.copyOfRange(32, raw.size)
        require(
            MessageDigest.isEqual(
                raw.copyOfRange(0, 32),
                MessageDigest.getInstance("SHA-256").digest(payload),
            ),
        )
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == 0x4c525331) { "Unsupported restriction journal" }
            val count = input.readInt()
            require(count in 0..10_000)
            val records =
                List(count) {
                    OwnedToolRestriction(
                            input.readUTF(),
                            PackageEnabledSetting.fromAndroid(input.readInt()),
                            input.readUTF(),
                            InstallationFingerprint(
                                input.readUTF(),
                                input.readLong(),
                                input.readLong(),
                            ),
                            input.readBoolean(),
                        )
                        .also {
                            require(
                                it.packageName.isNotBlank() &&
                                    it.ownerToken.isNotBlank() &&
                                    it.priorEnabledState.canBeRestricted(),
                            )
                        }
                }
            require(
                input.read() == -1 &&
                    records.map { it.packageName }.distinct().size == records.size,
            )
            records
        }
    }

    private fun replace(replacement: Map<String, OwnedToolRestriction>) {
        check(available) { "Restriction journal unavailable" }
        require(replacement.size <= 10_000)
        val payload =
            ByteArrayOutputStream()
                .also { bytes ->
                    DataOutputStream(bytes).use { output ->
                        output.writeInt(0x4c525331)
                        output.writeInt(replacement.size)
                        for (record in replacement.values.sortedBy { it.packageName }) {
                            val fingerprint = requireNotNull(record.installationFingerprint)
                            require(
                                record.packageName.isNotBlank() &&
                                    record.ownerToken.isNotBlank() &&
                                    record.priorEnabledState.canBeRestricted(),
                            )
                            output.writeUTF(record.packageName)
                            output.writeInt(record.priorEnabledState.androidValue)
                            output.writeUTF(record.ownerToken)
                            output.writeUTF(fingerprint.baseApkPath)
                            output.writeLong(fingerprint.firstInstallTimeMillis)
                            output.writeLong(fingerprint.lastUpdateTimeMillis)
                            output.writeBoolean(record.applied)
                        }
                    }
                }
                .toByteArray()
        val raw = MessageDigest.getInstance("SHA-256").digest(payload) + payload
        val parent = path.toAbsolutePath().parent
        var temporary: Path? = null
        try {
            Files.createDirectories(parent)
            temporary = Files.createTempFile(parent, ".restrictions-", ".tmp")
            FileChannel.open(temporary, WRITE).use {
                val buffer = ByteBuffer.wrap(raw)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
            FileChannel.open(parent, READ).use {
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                it.force(true)
            }
            current = replacement
        } catch (error: Exception) {
            available = false
            throw error
        } finally {
            temporary?.let { Files.deleteIfExists(it) }
        }
    }
}
