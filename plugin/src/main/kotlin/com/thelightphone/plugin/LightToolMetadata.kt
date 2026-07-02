package com.thelightphone.plugin

import org.tomlj.Toml
import org.tomlj.TomlInvalidTypeException
import org.tomlj.TomlParseResult
import java.io.File

/**
 * Parsed + validated representation of `lighttool.toml`. Every field has been
 * checked against the [LightToolPolicy] allowlists; downstream code can pass
 * these values straight into AGP and the generated manifest without further
 * sanitisation.
 *
 * Validation runs at Gradle configure time, so misconfigured tools fail
 * locally with the same message a dev would get from the build server.
 */
data class LightToolMetadata(
    val toolId: String,
    val label: String,
    val versionCode: Int,
    val versionName: String,
    val permissions: List<String>,
    val serverPackage: String,
) {
    companion object {
        const val FILE_NAME: String = "lighttool.toml"
        const val MAX_FILE_BYTES: Long = 32L * 1024

        /** Throws [LightToolMetadataException] if [file] is missing, malformed,
         * or violates the policy. The exception message is dev-facing. */
        fun parse(file: File): LightToolMetadata {
            if (!file.isFile) {
                throw LightToolMetadataException(
                    "missing $FILE_NAME at ${file.path} — declare your tool's metadata there"
                )
            }
            if (file.length() > MAX_FILE_BYTES) {
                throw LightToolMetadataException("$FILE_NAME exceeds $MAX_FILE_BYTES bytes")
            }

            val parsed: TomlParseResult = try {
                Toml.parse(file.toPath())
            } catch (e: Exception) {
                throw LightToolMetadataException("could not read $FILE_NAME: ${e.message}")
            }
            if (parsed.hasErrors()) {
                val msg = parsed.errors().joinToString("\n") { it.toString() }
                throw LightToolMetadataException("$FILE_NAME is not valid TOML:\n$msg")
            }

            val tool = parsed.getTable("tool")
                ?: throw LightToolMetadataException("$FILE_NAME is missing the [tool] table")

            return LightToolMetadata(
                toolId = validateToolId(tool.tomlString("id")),
                label = validateLabel(tool.tomlString("label")),
                versionCode = validateVersionCode(tool.tomlLong("versionCode")),
                versionName = validateVersionName(tool.tomlString("versionName")),
                permissions = validatePermissions(tool.tomlStringList("permissions")),
                serverPackage = validateServerPackage(tool.tomlString("serverPackage")),
            )
        }

        private fun validateToolId(value: String?): String {
            val v = value ?: throw LightToolMetadataException("tool.id is required")
            require(LightToolPolicy.TOOL_ID_PATTERN.matches(v)) {
                "tool.id must be a lowercase dotted Java package identifier " +
                        "(e.g. com.example.mytool); got '$v'"
            }
            return v
        }

        private fun validateLabel(value: String?): String {
            val v = value ?: throw LightToolMetadataException("tool.label is required")
            require(LightToolPolicy.TOOL_LABEL_PATTERN.matches(v)) {
                "tool.label must be 1-50 printable characters and contain no <, >, " +
                        "or control characters; got '$v'"
            }
            return v
        }

        private fun validateVersionCode(value: Long?): Int {
            val v = value ?: throw LightToolMetadataException("tool.versionCode is required")
            require(v in 1L..LightToolPolicy.MAX_VERSION_CODE.toLong()) {
                "tool.versionCode must be between 1 and ${LightToolPolicy.MAX_VERSION_CODE}; got $v"
            }
            return v.toInt()
        }

        private fun validateVersionName(value: String?): String {
            val v = value ?: throw LightToolMetadataException("tool.versionName is required")
            require(LightToolPolicy.VERSION_NAME_PATTERN.matches(v)) {
                "tool.versionName may contain only [A-Za-z0-9._+-] and must be <=30 chars; got '$v'"
            }
            return v
        }

        private fun validateServerPackage(value: String?): String {
            val v = value ?: throw LightToolMetadataException("tool.serverPackage is required")
            require(LightToolPolicy.TOOL_ID_PATTERN.matches(v)) {
                "tool.serverPackage must be a lowercase dotted Java package identifier " +
                        "(e.g. com.lightos); got '$v'"
            }
            return v
        }

        private fun validatePermissions(values: List<String>?): List<String> {
            val list = values ?: emptyList()
            val seen = mutableSetOf<String>()
            for (item in list) {
                require(seen.add(item)) { "duplicate permission: $item" }
                require(item in LightToolPolicy.ALLOWED_PERMISSIONS) {
                    "permission not allowed: $item\nallowed: ${LightToolPolicy.ALLOWED_PERMISSIONS.sorted().joinToString()}"
                }
            }
            return list
        }

        private fun require(condition: Boolean, lazyMessage: () -> String) {
            if (!condition) throw LightToolMetadataException(lazyMessage())
        }
    }
}

class LightToolMetadataException(message: String) : RuntimeException(message)

/** Policy values lifted into one object so tests and the validator share them. */
object LightToolPolicy {
    val TOOL_ID_PATTERN: Regex = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
    val VERSION_NAME_PATTERN: Regex = Regex("^[A-Za-z0-9._\\-+]{1,30}$")
    val TOOL_LABEL_PATTERN: Regex = Regex("^[^\\x00-\\x1f<>]{1,50}$")
    const val MAX_VERSION_CODE: Int = 2_100_000_000

    val ALLOWED_PERMISSIONS: Set<String> = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.WAKE_LOCK",
        "android.permission.VIBRATE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
    )

    /**
     * Permissions that Play Store / lint infer as also requiring a hardware
     * feature. Lacking a matching `<uses-feature>` element triggers
     * PermissionImpliesUnsupportedChromeOsHardware (and similar) lint
     * failures. We auto-emit each implied feature with `required="false"`
     * because every Light Phone has the hardware, and we don't want Play
     * Store filters excluding devices we don't actually need to exclude.
     */
    val PERMISSION_IMPLIED_FEATURES: Map<String, List<String>> = mapOf(
        "android.permission.CAMERA" to listOf("android.hardware.camera"),
        "android.permission.RECORD_AUDIO" to listOf("android.hardware.microphone"),
        "android.permission.ACCESS_FINE_LOCATION" to listOf("android.hardware.location.gps"),
        "android.permission.ACCESS_COARSE_LOCATION" to listOf("android.hardware.location.network"),
    )
}

// --- TomlTable extension helpers --------------------------------------------
// tomlj's TomlTable accessors can throw on type mismatches; wrap them so the
// caller gets a clean LightToolMetadataException instead of a TomlInvalidType.

private fun org.tomlj.TomlTable.tomlString(key: String): String? = try {
    if (contains(key)) getString(key) else null
} catch (e: TomlInvalidTypeException) {
    throw LightToolMetadataException("tool.$key must be a string")
}

private fun org.tomlj.TomlTable.tomlLong(key: String): Long? = try {
    if (contains(key)) getLong(key) else null
} catch (e: TomlInvalidTypeException) {
    throw LightToolMetadataException("tool.$key must be an integer")
}

private fun org.tomlj.TomlTable.tomlStringList(key: String): List<String>? {
    if (!contains(key)) return null
    val arr = try {
        getArray(key) ?: return null
    } catch (e: TomlInvalidTypeException) {
        throw LightToolMetadataException("tool.$key must be an array of strings")
    }
    val out = ArrayList<String>(arr.size())
    for (i in 0 until arr.size()) {
        val item = arr.get(i)
        if (item !is String) {
            throw LightToolMetadataException("tool.$key entries must be strings")
        }
        out.add(item)
    }
    return out
}
