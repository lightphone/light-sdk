package com.thelightphone.sdk.trust

import kotlinx.serialization.json.*

/** Bounded JSON reader that checks decoded object keys before constructing maps. */
internal class StrictJson(bytes: ByteArray) {
    private val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        reject(TrustFailure.InvalidJson("invalid UTF-8"))
    }
    private var offset = 0

    fun read(): JsonElement {
        val result = value(0)
        space()
        if (offset != text.length) bad()
        return result
    }

    private fun value(depth: Int): JsonElement {
        if (depth > 64) reject(TrustFailure.InvalidJson("nesting exceeds 64"))
        space()
        return when (text.getOrNull(offset)) {
            '{' -> {
                offset++
                val fields = linkedMapOf<String, JsonElement>()
                if (!take('}')) {
                    do {
                        space()
                        val key = string().content
                        if (fields.containsKey(key)) reject(TrustFailure.DuplicateKey(key))
                        if (!take(':')) bad()
                        fields[key] = value(depth + 1)
                    } while (take(','))
                    if (!take('}')) bad()
                }
                JsonObject(fields)
            }
            '[' -> {
                offset++
                val items = mutableListOf<JsonElement>()
                if (!take(']')) {
                    do { items.add(value(depth + 1)) } while (take(','))
                    if (!take(']')) bad()
                }
                JsonArray(items)
            }
            '"' -> string()
            else -> {
                val start = offset
                while (offset < text.length && text[offset] !in ",]} \t\r\n") offset++
                val token = text.substring(start, offset)
                if (token !in setOf("true", "false", "null") && !NUMBER.matches(token)) bad()
                Json.parseToJsonElement(token)
            }
        }
    }

    private fun string(): JsonPrimitive {
        val start = offset
        if (text.getOrNull(offset++) != '"') bad()
        while (offset < text.length) {
            when (text[offset++]) {
                '"' -> return try {
                    Json.parseToJsonElement(text.substring(start, offset)).jsonPrimitive
                } catch (_: IllegalArgumentException) { bad() }
                '\\' -> {
                    val escaped = text.getOrNull(offset++) ?: bad()
                    if (escaped == 'u') {
                        repeat(4) {
                            if ((text.getOrNull(offset++) ?: bad()) !in "0123456789abcdefABCDEF") bad()
                        }
                    } else if (escaped !in "\"\\/bfnrt") bad()
                }
                in '\u0000'..'\u001f' -> bad()
            }
        }
        bad()
    }

    private fun take(character: Char): Boolean {
        space()
        if (text.getOrNull(offset) != character) return false
        offset++
        return true
    }
    private fun space() { while (offset < text.length && text[offset] in " \t\r\n") offset++ }
    private fun bad(): Nothing = reject(TrustFailure.InvalidJson("invalid JSON at $offset"))
    companion object {
        private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}
