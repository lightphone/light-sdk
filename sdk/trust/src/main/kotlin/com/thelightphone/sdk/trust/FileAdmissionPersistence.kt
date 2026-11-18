package com.thelightphone.sdk.trust

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.Instant

/**
 * Dedicated private file; never point this backend at a stamp cache or bundle file.
 *
 * JSON payload, wrapped in the same checksum + atomic-write pattern as before. No prior
 * format to migrate from - nothing using LAD1/LAD2 was ever deployed. The checksum is
 * computed over the exact bytes before they are parsed, so a tampered file is rejected
 * before JSON parsing ever runs; a duplicate object key (which kotlinx.serialization
 * resolves silently, last-value-wins, rather than rejecting) could therefore only come
 * from a bug in [AdmissionRecord.toJson] itself, not from tampering - not worth adding
 * duplicate-key detection against an adversary the checksum already stops earlier.
 *
 * Only one instance may ever open a given path within this process - two live instances
 * over the same file would each hold an independently stale in-memory cache, since
 * [AdmissionStore] only reads at construction. This doesn't catch two *different* paths
 * being used where one was intended (that failure mode needs agreement on the path
 * itself, not a file-level guard); it only catches the same path opened twice.
 */
class FileAdmissionPersistence(private val path: Path) : AdmissionPersistence {
    init {
        val canonical = path.toAbsolutePath().normalize()
        synchronized(openPaths) {
            require(openPaths.add(canonical)) {
                "FileAdmissionPersistence is already open for $canonical in this process - " +
                    "only one owner per backend is safe"
            }
        }
    }

    override fun read(): List<AdmissionRecord> {
        val bytes = try { Files.readAllBytes(path) } catch (_: java.nio.file.NoSuchFileException) {
            return emptyList()
        }
        require(bytes.size >= 32) { "Truncated admission data" }
        val payload = bytes.copyOfRange(32, bytes.size)
        require(MessageDigest.isEqual(bytes.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(payload))) {
            "Corrupt admission data"
        }
        val root = Json.parseToJsonElement(payload.decodeToString()).jsonObject
        require(root.keys == setOf("records")) { "Unexpected admission document shape" }
        val records = root.getValue("records").jsonArray.map { it.jsonObject.toAdmissionRecord() }
        require(records.map { it.packageName }.distinct().size == records.size)
        return records
    }

    override fun write(records: List<AdmissionRecord>) {
        require(records.size <= 100_000)
        require(records.map { it.packageName }.distinct().size == records.size)
        val document = buildJsonObject {
            put("records", buildJsonArray {
                records.sortedBy { it.packageName }.forEach { add(it.toJson()) }
            })
        }
        val payload = document.toString().encodeToByteArray()
        val bytes = MessageDigest.getInstance("SHA-256").digest(payload) + payload
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

    companion object {
        private val openPaths = mutableSetOf<Path>()
    }
}

private fun AdmissionRecord.toJson(): JsonObject = buildJsonObject {
    put("packageName", packageName)
    put("apkSha256", apkSha256)
    put("signerSha256", signerSha256)
    toolId?.let { put("toolId", it) }
    put("versionCode", versionCode)
    admittedUnderBundleVersion?.let { put("admittedUnderBundleVersion", it) }
    put("admittedUnderLevel", admittedUnderLevel.name)
    put("admittedAtEpochSecond", admittedAt.epochSecond)
    put("admittedAtNano", admittedAt.nano)
    exception?.let { put("exception", it.name) }
    installationFingerprint?.let {
        put("fingerprintBaseApkPath", it.baseApkPath)
        put("fingerprintFirstInstallTimeMillis", it.firstInstallTimeMillis)
        put("fingerprintLastUpdateTimeMillis", it.lastUpdateTimeMillis)
    }
}

private fun JsonObject.requiredString(name: String): String =
    (getValue(name) as JsonPrimitive).let { require(it.isString); it.content }

private fun JsonObject.optionalString(name: String): String? =
    (this[name] as? JsonPrimitive)?.also { require(it.isString) }?.content

private fun JsonObject.requiredLong(name: String): Long = getValue(name).jsonPrimitive.long
private fun JsonObject.optionalLong(name: String): Long? = this[name]?.jsonPrimitive?.long

private fun JsonObject.toAdmissionRecord(): AdmissionRecord {
    val fingerprintPath = optionalString("fingerprintBaseApkPath")
    return AdmissionRecord(
        packageName = requiredString("packageName"),
        apkSha256 = requiredString("apkSha256"),
        signerSha256 = requiredString("signerSha256"),
        toolId = optionalString("toolId"),
        versionCode = requiredLong("versionCode"),
        admittedUnderBundleVersion = optionalLong("admittedUnderBundleVersion"),
        admittedUnderLevel = LightTrustFilterLevel.valueOf(requiredString("admittedUnderLevel")),
        admittedAt = Instant.ofEpochSecond(requiredLong("admittedAtEpochSecond"), requiredLong("admittedAtNano")),
        exception = optionalString("exception")?.let { AdmissionException.valueOf(it) },
        installationFingerprint = fingerprintPath?.let {
            InstallationFingerprint(
                it,
                requiredLong("fingerprintFirstInstallTimeMillis"),
                requiredLong("fingerprintLastUpdateTimeMillis"),
            )
        },
    )
}
