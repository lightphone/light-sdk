package com.thelightphone.sdk.trust

import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LightTrustVectorTest {
    private val vectors = Path.of(System.getProperty("lightTrustVectors"))
    private val statement = vectors.resolve("statement.json").readBytes()

    @Test
    fun `canonical bytes match Python vector`() {
        assertContentEquals(
            vectors.resolve("statement.canonical.json").readBytes(),
            LightTrustCanonicalizer.statementBytes(statement),
        )
    }

    @Test
    fun `unicode remains UTF-8`() {
        val canonical = LightTrustCanonicalizer.statementBytes(statement).decodeToString()
        assertTrue("luz-☀" in canonical)
        assertFalse("\\u2600" in canonical)
    }

    @Test
    fun `reordered keys canonicalize identically`() {
        val root = Json.parseToJsonElement(statement.decodeToString()).jsonObject
        val reordered = JsonObject(root.entries.reversed().associate { it.toPair() })
            .toString()
            .encodeToByteArray()
        assertContentEquals(
            LightTrustCanonicalizer.statementBytes(statement),
            LightTrustCanonicalizer.statementBytes(reordered),
        )
    }

    @Test
    fun `vector signature verifies`() {
        val document = Json.parseToJsonElement(statement.decodeToString()).jsonObject
        val signature = Base64.getDecoder().decode(
            document.getValue("attestation").jsonObject.getValue("sig").jsonPrimitive.content
        )
        assertTrue(
            LightAttestationVerifier.verify(
                LightTrustCanonicalizer.statementBytes(statement),
                signature,
                vectors.resolve("test-attestation-public.pem").readText(),
            )
        )
    }
}
