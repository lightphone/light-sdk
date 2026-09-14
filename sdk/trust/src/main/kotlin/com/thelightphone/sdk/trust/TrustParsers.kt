package com.thelightphone.sdk.trust

import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.Collections

internal fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
internal fun <T> frozenSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(values.toSet())
private fun invalid(path: String): Nothing = reject(TrustFailure.InvalidField(path))
private fun JsonElement.obj(path: String): JsonObject = this as? JsonObject ?: invalid(path)
private fun JsonElement.array(path: String): JsonArray = this as? JsonArray ?: invalid(path)
private fun JsonObject.fields(vararg names: String) { if (keys != names.toSet()) invalid("fields: ${names.joinToString()}") }
private fun JsonObject.field(name: String): JsonElement = get(name) ?: invalid(name)
private fun JsonObject.string(name: String): String {
    val value = field(name) as? JsonPrimitive ?: invalid(name)
    if (!value.isString || value.content.isEmpty()) invalid(name)
    return value.content
}
private fun JsonObject.number(name: String): Long {
    val value = field(name) as? JsonPrimitive ?: invalid(name)
    if (value.isString || !Regex("-?(0|[1-9][0-9]*)").matches(value.content)) invalid(name)
    return value.content.toLongOrNull()?.takeIf { it >= 0 } ?: invalid(name)
}
private fun JsonObject.schema() {
    val version = field("schemaVersion") as? JsonPrimitive ?: invalid("schemaVersion")
    if (version.isString || !Regex("[0-9]+").matches(version.content)) invalid("schemaVersion")
    if (version.content != "1") reject(TrustFailure.UnsupportedSchema(version.content))
}
private fun JsonObject.hash(name: String): String = string(name).also {
    if (!Regex("[0-9a-f]{64}").matches(it)) invalid(name)
}
private fun JsonObject.timestamp(): String = string("issuedAt").also {
    if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z").matches(it) || it.startsWith("0000")) invalid("issuedAt")
    try { LocalDateTime.parse(it.dropLast(1)) } catch (_: DateTimeParseException) { invalid("issuedAt") }
}
private fun JsonObject.certs(name: String): Set<String> {
    val values = field(name).array(name).map {
        JsonObject(mapOf(name to it)).hash(name)
    }
    if (values.distinct().size != values.size) invalid(name)
    return frozenSet(values)
}

object LightTrustStatementParser {
    /** Parsing is structural only; callers must first verify the containing APK's stamp. */
    fun parse(bytes: ByteArray): TrustResult<LightTrustStatement> = parsed {
        val root = StrictJson(bytes).read().obj("statement")
        root.schema()
        val tool = root.field("tool").obj("tool")
        // Extra statement fields cannot confer approval; only the bundle does that.
        LightTrustStatement(1, LightTrustTool(tool.string("id"), tool.number("versionCode"),
            tool.string("versionName"), tool.string("gitUrl"), tool.string("gitCommit")),
            root.string("sdkGitRef"), root.string("devId"), root.hash("signerSha256"),
            root.string("buildId"), root.timestamp())
    }
}

object LightTrustBundleParser {
    fun parse(bytes: ByteArray): TrustResult<LightTrustBundle> = parsed {
        val root = StrictJson(bytes).read().obj("bundle")
        root.schema()
        root.fields("schemaVersion", "version", "issuedAt", "allow", "block", "trustedStampCerts", "revokedStampCerts")
        val approvals = root.field("allow").array("allow").map { raw ->
            val entry = raw.obj("allow")
            entry.fields("toolId", "signerSha256", "minVersionCode", "approvedArtifacts")
            val artifacts = entry.field("approvedArtifacts").array("approvedArtifacts").map {
                val artifact = it.obj("approvedArtifact")
                when (artifact.keys) {
                    setOf("buildId") -> ApprovedArtifact.BuildId(artifact.string("buildId"))
                    setOf("apkSha256") -> ApprovedArtifact.ApkSha256(artifact.hash("apkSha256"))
                    else -> invalid("approvedArtifact")
                }
            }
            if (artifacts.isEmpty()) invalid("approvedArtifacts")
            TrustApproval(entry.string("toolId"), entry.hash("signerSha256"), entry.number("minVersionCode"), frozen(artifacts))
        }
        if (approvals.map { it.toolId }.distinct().size != approvals.size) invalid("duplicate allow toolId")
        val blocks = root.field("block").array("block").map { raw ->
            val entry = raw.obj("block")
            entry.fields("match", "action", "reason")
            val match = entry.field("match").obj("match")
            val parsedMatch = when (match.keys) {
                setOf("signerSha256") -> BlockMatch.SignerSha256(match.hash("signerSha256"))
                setOf("toolId") -> BlockMatch.ToolId(match.string("toolId"))
                setOf("toolId", "versionCode") -> BlockMatch.ToolVersion(match.string("toolId"), match.number("versionCode"))
                setOf("apkSha256") -> BlockMatch.ApkSha256(match.hash("apkSha256"))
                else -> invalid("block.match")
            }
            val action = when (entry.string("action")) {
                "block" -> BlockAction.Block
                "purge" -> BlockAction.Purge
                else -> invalid("block.action")
            }
            TrustBlock(parsedMatch, action, entry.string("reason"))
        }
        LightTrustBundle(1, root.number("version"), root.timestamp(), frozen(approvals), frozen(blocks),
            root.certs("trustedStampCerts"), root.certs("revokedStampCerts"))
    }
}
