package com.thelightphone.sdk.trust

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.test.*

class TrustBundleTest {
    @Test fun `pin encoding matrix rejects invalid pins in either order`() {
        // start with a valid fixture key
        val good = pem("INSECURE-bundle-public.pem")
        // the 32 key bytes after the 12-byte SPKI header
        val raw = good.copyOfRange(12, good.size)
        val oid = org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.101.112")
        val algorithm = org.bouncycastle.asn1.x509.AlgorithmIdentifier(oid)
        fun spki(key: ByteArray) = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo(algorithm, key).getEncoded("DER")
        val rsa = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded
        // build a bunch of invalid keys
        val invalid = listOf(
            // valid key with extra data at the end
            good + byteArrayOf(0),
            // BER entry with long form length, DER requires the short form
            byteArrayOf(0x30, 0x81.toByte(), 0x2a) + good.copyOfRange(2, good.size),
            // X25519 algorithm identifier (instead of Ed25519)
            good.copyOf().apply { this[8] = 0x6e },
            // drop final key byte without updating the encoded length
            good.copyOf(good.size - 1),
            // valid public key for an unsupported algorithm
            rsa,
            // Ed25519 parameters must be absent, not explicitly NULL
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo(
                org.bouncycastle.asn1.x509.AlgorithmIdentifier(oid, org.bouncycastle.asn1.DERNull.INSTANCE), raw
            ).getEncoded("DER"),
            // public key bits must fill whole bytes, without unused padding bits
            org.bouncycastle.asn1.DERSequence(arrayOf<org.bouncycastle.asn1.ASN1Encodable>(
                algorithm, org.bouncycastle.asn1.DERBitString(raw.copyOf().apply { this[lastIndex] = 0 }, 1)
            )).getEncoded("DER"),
            // structurally valid wrappers containing too few or too many key bytes
            spki(raw.copyOf(31)), spki(raw.copyOf(33)),
            // missing or NULL ASN.1 object instead of a public key
            byteArrayOf(), byteArrayOf(5, 0),
        )

        // every configured key must be valid
        val pair = fixture("valid")
        invalid.forEachIndexed { index, bad ->
            for (pins in listOf(listOf(good, bad), listOf(bad, good))) {
                assertEquals(TrustFailure.InvalidKey, failure(LightTrustBundleVerifier(pins).verify(pair.bytes, pair.signature)), "case $index")
            }
        }

        // signature must have exactly 64 bytes
        for (length in listOf(0, 63, 65)) {
            assertEquals(TrustFailure.InvalidSignature, failure(verifier().verify(pair.bytes, ByteArray(length))))
        }
    }

    @Test fun `image upgrade persists atomically and does not discard corrupt disk`() {
        // image ships bundle v42; the device last fetched v41
        val image = fixture("valid")
        val old = signed(validText().replace("\"version\": 42", "\"version\": 41"))
        val memory = Memory().apply { record = old; fail = true }

        // the image is newer, so open() must persist it first;
        // when that write fails the store refuses to open rather than serving v42 from memory
        // and silently reverting to v41 on the next boot
        assertEquals(TrustFailure.PersistenceFailed, failure(LightTrustStore.open(verifier(), memory, emptySet(), image)))

        // the failed write left the old record intact
        assertSame(old, memory.record)

        // retry succeeds, and reopening without an image proves v42 was persisted
        memory.fail = false
        success(LightTrustStore.open(verifier(), memory, emptySet(), image))
        assertEquals(42, success(LightTrustStore.open(verifier(), memory, emptySet())).state().version)

        // a new bundle v43 is fetched and open() keeps it
        val newer = signed(validText().replace("\"version\": 42", "\"version\": 43"))
        memory.record = newer
        val writes = memory.writes
        assertEquals(43, success(LightTrustStore.open(verifier(), memory, emptySet(), image)).state().version)

        // open() selected the record already on disk, so it writes nothing
        assertEquals(writes, memory.writes)

        // keep bundle v42 and drops minVersionCode to 1,
        // re-allowing an old tool without ever looking like a rollback.
        // open() replaces only on a higher edition, so the old bundle (minVersionCode = 7) will remain
        memory.record = signed(validText().replace("\"minVersionCode\": 7", "\"minVersionCode\": 1"))
        assertEquals(7, success(LightTrustStore.open(verifier(), memory, emptySet(), image)).state().bundle!!.allow.single().minVersionCode)
        // bad file on disk was replaced
        assertContentEquals(image.bytes, memory.record!!.bytes)

        // a non-Light signature in device storage means someone swapped the file
        // open() reports it instead of quietly falling back to the image,
        // which would boot a tampered device looking perfectly healthy
        memory.record = fixture("foreign-key")
        assertEquals(TrustFailure.InvalidSignature, failure(LightTrustStore.open(verifier(), memory, emptySet(), image)))
    }

    private fun resource(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/bundle/$name"))
            .use { it.readBytes() }

    private fun pem(name: String) =
        Base64.getDecoder().decode(resource(name)
            .decodeToString()
            .lines()
            .filterNot { it.startsWith("---") }
            .joinToString(""))

    private fun verifier() =
        LightTrustBundleVerifier(listOf(pem("INSECURE-bundle-public.pem")))

    private fun fixture(name: String) =
        SignedTrustBundle(resource("$name/bundle.json"), resource("$name/bundle.sig"))

    private fun signed(text: String): SignedTrustBundle {
        val bytes = text.encodeToByteArray()
        val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(pem("INSECURE-bundle-private.pem")))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(key)
        signer.update(LightTrustBundleFormat.signedPayloadPrefix)
        signer.update(bytes)
        return SignedTrustBundle(bytes, signer.sign())
    }

    private fun validText() =
        resource("valid/bundle.json").decodeToString()

    private fun <T> success(result: TrustResult<T>): T =
        assertIs<TrustResult.Success<T>>(result).value

    private fun failure(result: TrustResult<*>) =
        assertIs<TrustResult.Failure>(result).reason

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

    @Test fun `bundle signed in the python script verifies fine`() {
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

    @Test fun `one invalid pin rejects the whole set, but a non-matching valid pin does not`() {
        val good = pem("INSECURE-bundle-public.pem")
        val foreign = pem("INSECURE-foreign-public.pem")
        val pair = fixture("valid")
        val cases = listOf(
            listOf(good, byteArrayOf(1)),
            listOf(byteArrayOf(1), good),
            emptyList()
        )
        for (pins in cases) {
            assertEquals(TrustFailure.InvalidKey, failure(LightTrustBundleVerifier(pins).verify(pair.bytes, pair.signature)))
        }
        success(LightTrustBundleVerifier(listOf(foreign, good)).verify(pair.bytes, pair.signature))
    }

    @Test fun `state, version floor and storage bundle remain untouched on rejected update`() {
        val cases = listOf(
            Triple(
                "8 rollback",
                signed(validText().replace("\"version\": 42", "\"version\": 41")),
                TrustFailure.VersionNotNewer::class
            ),
            Triple(
                "9 foreign key",
                fixture("foreign-key"),
                TrustFailure.InvalidSignature::class
            ),
            Triple(
                "14 lower approval floor",
                signed(validText().replace("\"version\": 42", "\"version\": 41").replace("\"minVersionCode\": 7", "\"minVersionCode\": 1")),
                TrustFailure.VersionNotNewer::class
            ),
            Triple(
                "18 newer schema",
                fixture("newer-schema"),
                TrustFailure.UnsupportedSchema::class
            ),
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

    @Test fun `revocation can remove pins from built-in bundle and a later bundle update can restore it`() {
        // dummy certificate hashes used by the fixture
        val a = "a".repeat(64);
        val b = "b".repeat(64);
        val c = "c".repeat(64);
        val d = "d".repeat(64)

        val memory = Memory()
        val expected = Json.parseToJsonElement(resource("expected-trust-set.json").decodeToString()).jsonObject
        fun hashes(name: String) = expected.getValue(name).jsonArray.map { it.jsonPrimitive.content }.toSet()

        // the image pins A, B and D
        // v42 bundle trusts C and revokes A and B
        // D stays because the bundle never mentions it,
        // C is added
        val store = success(LightTrustStore.open(verifier(), memory, hashes("imagePins"), fixture("valid")))
        assertEquals(hashes("trusted"), store.state().trustedStampCerts)

        // a newer bundle that revokes nothing
        val next = signed("""{"schemaVersion":1,"version":43,"issuedAt":"2026-08-25T00:00:00Z","allow":[],"block":[],"trustedStampCerts":[],"revokedStampCerts":[]}""")
        success(store.accept(next))
        assertEquals(setOf(a, b, d), store.state().trustedStampCerts)

        // reopen from the same storage with no built-in bundle
        assertEquals(store.state(), success(LightTrustStore.open(verifier(), memory, setOf(a, b, d))).state())

        // no old bundle can be replayed to undo a change
        assertIs<TrustFailure.VersionNotNewer>(failure(store.accept(next)))
    }

    @Test fun `omission keeps an image pin, explicit revocation removes it, deny wins`() {
        val a = "a".repeat(64);
        val b = "b".repeat(64)

        data class Case(val name: String, val trust: String, val revoke: String, val expected: Set<String>)
        val cases = listOf(
            Case("image pin cannot be removed by omission", "", "", setOf(a)),
            Case("delegated cert added", "\"$b\"", "", setOf(a, b)),
            Case("17 revoked image cert", "", "\"$a\"", emptySet()),
            Case("19 deny wins", "\"$a\",\"$b\"", "\"$a\",\"$b\"", emptySet())
        )
        for (case in cases) {
            val record = signed("""{"schemaVersion":1,"version":1,"issuedAt":"2026-08-25T00:00:00Z","allow":[],"block":[],"trustedStampCerts":[${case.trust}],"revokedStampCerts":[${case.revoke}]}""")
            val store = success(LightTrustStore.open(verifier(), Memory(), setOf(a), record))
            assertEquals(case.expected, store.state().trustedStampCerts, case.name)
        }
    }

    @Test fun `boot re-checks stored bytes and adopts the newer image and storage`() {
        val memory = Memory()

        // 1. storage has somehow a bundle signed by the wrong key
        memory.record = fixture("foreign-key")
        assertEquals(TrustFailure.InvalidSignature, failure(LightTrustStore.open(verifier(), memory, emptySet())))

        // 2. got an OTA upgrade: storage has v41, and fw image has v42.
        memory.record = signed(validText().replace("\"version\": 42", "\"version\": 41"))
        val upgraded = success(LightTrustStore.open(verifier(), memory, emptySet(), fixture("valid")))
        assertEquals(42, upgraded.state().version)
        assertContentEquals(fixture("valid").bytes, memory.record!!.bytes)

        // 3. storage can't be read
        val broken = object : TrustPersistence {
            override fun read(): SignedTrustBundle? = error("read failed")
            override fun write(bundle: SignedTrustBundle) = Unit
        }
        assertEquals(TrustFailure.PersistenceFailed, failure(LightTrustStore.open(verifier(), broken, emptySet())))

        // 4. empty storage and firmware
        assertNull(success(LightTrustStore.open(verifier(), Memory(), emptySet())).state().version)
    }

    @Test fun `nothing the caller holds is shared with verified state`() {
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

    @Test fun `parsers return a typed failure and not exception` () {
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
