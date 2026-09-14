package com.thelightphone.sdk.trust

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.test.*

class TrustBundleTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/bundle/$name")).use { it.readBytes() }
    private fun pem(name: String) = Base64.getDecoder().decode(resource(name).decodeToString().lines().filterNot { it.startsWith("---") }.joinToString(""))
    private fun verifier() = LightTrustBundleVerifier(listOf(pem("INSECURE-bundle-public.pem")))
    private fun fixture(name: String) = SignedTrustBundle(resource("$name/bundle.json"), resource("$name/bundle.sig"))
    private fun signed(text: String): SignedTrustBundle {
        val bytes = text.encodeToByteArray()
        val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(pem("INSECURE-bundle-private.pem")))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(key)
        signer.update(LightTrustBundleFormat.signedPayloadPrefix)
        signer.update(bytes)
        return SignedTrustBundle(bytes, signer.sign())
    }
    private fun validText() = resource("valid/bundle.json").decodeToString()
    private fun <T> success(result: TrustResult<T>): T = assertIs<TrustResult.Success<T>>(result).value
    private fun failure(result: TrustResult<*>) = assertIs<TrustResult.Failure>(result).reason
    private class Memory : TrustPersistence {
        var record: SignedTrustBundle? = null
        var fail = false
        var writes = 0
        override fun read() = record
        override fun write(bundle: SignedTrustBundle) {
            if (fail) error("disk full")
            record = bundle
            writes++
        }
    }

    @Test fun `Python vectors verify byte exact`() {
        val verify = verifier()
        val valid = fixture("valid")
        assertEquals(42, success(verify.verify(valid.bytes, valid.signature)).version)
        for (name in listOf("foreign-key", "edited-payload", "whitespace-edit", "no-domain-separator")) {
            val pair = fixture(name)
            assertEquals(TrustFailure.InvalidSignature, failure(verify.verify(pair.bytes, pair.signature)), name)
        }
        val newer = fixture("newer-schema")
        assertIs<TrustFailure.UnsupportedSchema>(failure(verify.verify(newer.bytes, newer.signature)))
    }

    @Test fun `signature verification precedes parsing`() {
        val malformed = "not JSON".encodeToByteArray()
        assertEquals(TrustFailure.InvalidSignature, failure(verifier().verify(malformed, ByteArray(64))))
        val authenticated = signed("not JSON")
        assertIs<TrustFailure.InvalidJson>(failure(verifier().verify(authenticated.bytes, authenticated.signature)))
    }

    @Test fun `all pins checked regardless of order and later good pin works`() {
        val good = pem("INSECURE-bundle-public.pem")
        val foreign = pem("INSECURE-foreign-public.pem")
        val pair = fixture("valid")
        for (pins in listOf(listOf(good, byteArrayOf(1)), listOf(byteArrayOf(1), good), emptyList())) {
            assertEquals(TrustFailure.InvalidKey, failure(LightTrustBundleVerifier(pins).verify(pair.bytes, pair.signature)))
        }
        success(LightTrustBundleVerifier(listOf(foreign, good)).verify(pair.bytes, pair.signature))
    }

    @Test fun `threat rows retain state on rejected updates`() {
        val cases = listOf(
            Triple("8 rollback", signed(validText().replace("\"version\": 42", "\"version\": 41")), TrustFailure.VersionNotNewer::class),
            Triple("9 foreign key", fixture("foreign-key"), TrustFailure.InvalidSignature::class),
            Triple("14 lower approval floor", signed(validText().replace("\"version\": 42", "\"version\": 41").replace("\"minVersionCode\": 7", "\"minVersionCode\": 1")), TrustFailure.VersionNotNewer::class),
            Triple("18 newer schema", fixture("newer-schema"), TrustFailure.UnsupportedSchema::class),
        )
        for ((name, candidate, expected) in cases) {
            val memory = Memory()
            val store = success(LightTrustStore.open(verifier(), memory, setOf("a".repeat(64)), fixture("valid")))
            val before = store.state()
            assertEquals(expected, failure(store.accept(candidate))::class, name)
            assertSame(before, store.state(), name)
            assertEquals(1, memory.writes, name)
            assertEquals(7, store.state().bundle!!.allow.single().minVersionCode)
        }
    }

    @Test fun `rows 17 and 19 deny wins including image pins and declarative correction`() {
        val a = "a".repeat(64); val b = "b".repeat(64); val c = "c".repeat(64); val d = "d".repeat(64)
        val memory = Memory()
        val expected = Json.parseToJsonElement(resource("expected-trust-set.json").decodeToString()).jsonObject
        fun hashes(name: String) = expected.getValue(name).jsonArray.map { it.jsonPrimitive.content }.toSet()
        val store = success(LightTrustStore.open(verifier(), memory, hashes("imagePins"), fixture("valid")))
        assertEquals(hashes("trusted"), store.state().trustedStampCerts)
        val next = signed("""{"schemaVersion":1,"version":43,"issuedAt":"2026-08-25T00:00:00Z","allow":[],"block":[],"trustedStampCerts":[],"revokedStampCerts":[]}""")
        success(store.accept(next))
        assertEquals(setOf(a, b, d), store.state().trustedStampCerts)
        assertEquals(store.state(), success(LightTrustStore.open(verifier(), memory, setOf(a, b, d))).state())
        assertIs<TrustFailure.VersionNotNewer>(failure(store.accept(next)))
    }

    @Test fun `trust set table denies revocation and preserves omitted image pins`() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        data class Case(val name: String, val trust: String, val revoke: String, val expected: Set<String>)
        for (case in listOf(
            Case("image pin cannot be removed by omission", "", "", setOf(a)),
            Case("delegated cert added", "\"$b\"", "", setOf(a, b)),
            Case("17 revoked image cert", "", "\"$a\"", emptySet()),
            Case("19 deny wins", "\"$a\",\"$b\"", "\"$a\",\"$b\"", emptySet()),
        )) {
            val record = signed("""{"schemaVersion":1,"version":1,"issuedAt":"2026-08-25T00:00:00Z","allow":[],"block":[],"trustedStampCerts":[${case.trust}],"revokedStampCerts":[${case.revoke}]}""")
            val store = success(LightTrustStore.open(verifier(), Memory(), setOf(a), record))
            assertEquals(case.expected, store.state().trustedStampCerts, case.name)
        }
    }

    @Test fun `restart verifies disk and refuses disk below image floor`() {
        val memory = Memory()
        memory.record = fixture("foreign-key")
        assertEquals(TrustFailure.InvalidSignature, failure(LightTrustStore.open(verifier(), memory, emptySet())))
        memory.record = signed(validText().replace("\"version\": 42", "\"version\": 41"))
        assertIs<TrustFailure.VersionNotNewer>(failure(LightTrustStore.open(verifier(), memory, emptySet(), fixture("valid"))))
        val broken = object : TrustPersistence {
            override fun read(): SignedTrustBundle? = error("read failed")
            override fun write(bundle: SignedTrustBundle) = Unit
        }
        assertEquals(TrustFailure.PersistenceFailed, failure(LightTrustStore.open(verifier(), broken, emptySet())))
        assertNull(success(LightTrustStore.open(verifier(), Memory(), emptySet())).state().version)
    }

    @Test fun `caller mutation cannot alter authenticated state`() {
        val public = pem("INSECURE-bundle-public.pem")
        val verify = LightTrustBundleVerifier(listOf(public))
        public.fill(0)
        val pair = fixture("valid")
        pair.bytes.fill(0)
        pair.signature.fill(0)
        LightTrustBundleFormat.signedPayloadPrefix.fill(0)
        val pins = mutableSetOf("d".repeat(64))
        val store = success(LightTrustStore.open(verify, Memory(), pins, pair))
        pins.clear()
        assertContains(store.state().trustedStampCerts, "d".repeat(64))
        assertFailsWith<UnsupportedOperationException> { (store.state().bundle!!.allow as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (store.state().trustedStampCerts as MutableSet).clear() }
    }

    @Test fun `arbitrary bounded input cannot escape parsers`() {
        val random = Random(5)
        repeat(1000) {
            val bytes = random.nextBytes(random.nextInt(0, 256))
            LightTrustBundleParser.parse(bytes)
            LightTrustStatementParser.parse(bytes)
        }
        for (text in listOf("{\"x\":\"\\u12\"}", "{\"x\":\"\\q\"}", "{\"x\":\"\n\"}", "{\"x\":\"unterminated}")) {
            assertIs<TrustFailure.InvalidJson>(failure(LightTrustBundleParser.parse(text.encodeToByteArray())))
        }
    }

    @Test fun `persistence failure preserves floor and allows retry`() {
        val memory = Memory()
        val store = success(LightTrustStore.open(verifier(), memory, emptySet(), fixture("valid")))
        val before = store.state()
        memory.fail = true
        val next = signed(validText().replace("\"version\": 42", "\"version\": 43"))
        assertEquals(TrustFailure.PersistenceFailed, failure(store.accept(next)))
        assertSame(before, store.state())
        memory.fail = false
        success(store.accept(next))
        assertEquals(43, store.state().version)
    }

    @Test fun `malformed JSON always returns typed failure`() {
        for (text in listOf("", "{", "null", "[]", "{\"x\":1,}", "{\"x\":01}", "{\"x\":NaN}", "[".repeat(66) + "0" + "]".repeat(66))) {
            assertIs<TrustResult.Failure>(LightTrustBundleParser.parse(text.encodeToByteArray()), text)
            assertIs<TrustResult.Failure>(LightTrustStatementParser.parse(text.encodeToByteArray()), text)
        }
        assertIs<TrustFailure.InvalidJson>(failure(LightTrustBundleParser.parse(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))))
        for (text in listOf("{\"x\":1,\"x\":2}", "{\"x\":{\"a\":1,\"\\u0061\":2}}")) {
            assertIs<TrustFailure.DuplicateKey>(failure(LightTrustBundleParser.parse(text.encodeToByteArray())))
        }
    }

    @Test fun `bundle structure and numeric boundaries`() {
        for (text in listOf(
            validText().replace("\"version\": 42", "\"version\": 9223372036854775808"),
            validText().replace("\"version\": 42", "\"version\": true"),
            validText().replace("\"version\": 42", "\"version\": 1.0"),
            validText().replace("2026-08-25", "2026-02-30"),
            validText().replace("\"purge\"", "[]"),
            validText().replace("\"minVersionCode\"", "\"typo\""),
            validText().replace("\"buildId\"", "\"unknown\""),
        )) assertIs<TrustFailure.InvalidField>(failure(LightTrustBundleParser.parse(text.encodeToByteArray())))
        assertEquals(Long.MAX_VALUE, success(LightTrustBundleParser.parse(validText().replace("\"version\": 42", "\"version\": 9223372036854775807").encodeToByteArray())).version)
        assertIs<TrustFailure.UnsupportedSchema>(failure(LightTrustBundleParser.parse("""{"schemaVersion":2,"future":true}""".encodeToByteArray())))
    }

    @Test fun `statement parses without approval authority`() {
        val text = """{"schemaVersion":1,"tool":{"id":"com.example.tool","versionCode":7,"versionName":"1.0","gitUrl":"https://example.com/repo","gitCommit":"abc"},"sdkGitRef":"v1","devId":"dev","signerSha256":"${"a".repeat(64)}","buildId":"build_01","issuedAt":"2026-08-25T00:00:00Z"}"""
        val statement = success(LightTrustStatementParser.parse(text.encodeToByteArray()))
        assertEquals(statement, success(LightTrustStatementParser.parse(text.dropLast(1).plus(",\"approved\":true}").encodeToByteArray())))
        assertIs<TrustResult.Failure>(LightTrustStatementParser.parse(text.replace("\"buildId\"", "\"missing\"").encodeToByteArray()))
    }
}
