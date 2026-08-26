package com.thelightphone.sdk.trust

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object LightTrustCanonicalizer {
    private val integerPattern = Regex("-?(0|[1-9][0-9]*)")
    private val keyComparator = Comparator<String> { left, right -> compareCodePoints(left, right) }

    fun statementBytes(document: ByteArray): ByteArray {
        val root = Json.parseToJsonElement(document.decodeToString()) as? JsonObject
            ?: throw IllegalArgumentException("trust statement must be a JSON object")
        return canonicalObject(JsonObject(root - "attestation")).encodeToByteArray()
    }

    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> canonicalObject(element)
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::canonical)
        JsonNull -> "null"
        is JsonPrimitive -> canonicalPrimitive(element)
    }

    private fun canonicalObject(value: JsonObject): String = value.entries
        .sortedWith { left, right -> keyComparator.compare(left.key, right.key) }
        .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, child) ->
            "${quote(key)}:${canonical(child)}"
        }

    private fun canonicalPrimitive(value: JsonPrimitive): String {
        if (value.isString) return quote(value.content)
        require(value.content in setOf("true", "false") || integerPattern.matches(value.content)) {
            "floats are not allowed in trust documents"
        }
        return value.content
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun compareCodePoints(left: String, right: String): Int {
        val leftPoints = left.codePoints().iterator()
        val rightPoints = right.codePoints().iterator()
        while (leftPoints.hasNext() && rightPoints.hasNext()) {
            val comparison = leftPoints.nextInt().compareTo(rightPoints.nextInt())
            if (comparison != 0) return comparison
        }
        return leftPoints.hasNext().compareTo(rightPoints.hasNext())
    }
}
