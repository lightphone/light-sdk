package com.thelightphone.sdk.trust

import com.thelightphone.sdk.shared.ToolFilterLevel
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Persists [AdmissionRecord]s to a dedicated private file, one record per package.
 *
 * File format is a JSON document `{"records": [...]}` prefixed by the 32-byte SHA-256 checksum of
 * the JSON bytes. Records are written sorted by package name, so a given snapshot always serializes
 * to the same bytes.
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
        val bytes =
            try {
                Files.readAllBytes(path)
            } catch (_: java.nio.file.NoSuchFileException) {
                return emptyList()
            }
        require(bytes.size >= 32) { "Truncated admission data" }
        val payload = bytes.copyOfRange(32, bytes.size)
        // the checksum is unkeyed and meant to detect accidental corruption, not tampering.
        require(
            MessageDigest.isEqual(
                bytes.copyOfRange(0, 32),
                MessageDigest.getInstance("SHA-256").digest(payload),
            )
        ) {
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
            put(
                "records",
                buildJsonArray { records.sortedBy { it.packageName }.forEach { add(it.toJson()) } },
            )
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
            // only delete after the whole operation is done, so that the previous snapshot can be
            // used for recovery
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
    put("admittedAtEpochSecond", admittedAt.epochSeconds)
    put("admittedAtNano", admittedAt.nanosecondsOfSecond)
    exception?.let { put("exception", it.name) }
    installationFingerprint?.let {
        put("fingerprintBaseApkPath", it.baseApkPath)
        put("fingerprintFirstInstallTimeMillis", it.firstInstallTimeMillis)
        put("fingerprintLastUpdateTimeMillis", it.lastUpdateTimeMillis)
    }
}

private fun JsonObject.requiredString(name: String): String =
    (getValue(name) as JsonPrimitive).let {
        require(it.isString)
        it.content
    }

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
        admittedUnderLevel = ToolFilterLevel.valueOf(requiredString("admittedUnderLevel")),
        admittedAt =
            Instant.fromEpochSeconds(
                requiredLong("admittedAtEpochSecond"),
                requiredLong("admittedAtNano"),
            ),
        exception = optionalString("exception")?.let { AdmissionException.valueOf(it) },
        installationFingerprint =
            fingerprintPath?.let {
                InstallationFingerprint(
                    it,
                    requiredLong("fingerprintFirstInstallTimeMillis"),
                    requiredLong("fingerprintLastUpdateTimeMillis"),
                )
            },
    )
}
