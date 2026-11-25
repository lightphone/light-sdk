package com.thelightphone.sdk.trust

import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class TrustBundleCandidateStagingTest {
    private class Memory : TrustPersistence {
        var record: SignedTrustBundle? = null

        override fun read() = record

        override fun write(bundle: SignedTrustBundle) {
            record = bundle
        }
    }

    private fun resource(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/bundle/$name")).use { it.readBytes() }

    private fun pem(name: String) =
        Base64.getDecoder()
            .decode(
                resource(name)
                    .decodeToString()
                    .lines()
                    .filterNot { it.startsWith("---") }
                    .joinToString("")
            )

    private fun verifier() = LightTrustBundleVerifier(listOf(pem("INSECURE-bundle-public.pem")))

    private fun fixture(name: String) =
        SignedTrustBundle(resource("$name/bundle.json"), resource("$name/bundle.sig"))

    private fun openStore(accepted: TrustPersistence = Memory()) =
        (LightTrustStore.open(verifier(), accepted, emptySet()) as TrustResult.Success).value

    @Test
    fun `staged candidate is readable before submission and unaffected by the accepted store`() {
        val staging = TrustBundleCandidateStaging(Memory())
        assertNull(staging.candidate())

        val candidate = fixture("valid")
        staging.stage(candidate)

        val read = staging.candidate()
        assertNotNull(read)
        assertContentEquals(candidate.bytes, read.bytes)
        assertContentEquals(candidate.signature, read.signature)
    }

    @Test
    fun `submitting a staged candidate accepts it into the store`() {
        val accepted = Memory()
        val store = openStore(accepted)
        val staging = TrustBundleCandidateStaging(Memory())
        val candidate = fixture("valid")
        staging.stage(candidate)

        val result = staging.submit(store, candidate)

        assertIs<TrustResult.Success<TrustState>>(result)
        assertNotNull(accepted.record)
        assertEquals(result.value, store.state())
    }

    @Test
    fun `an unavailable store exposes no state and rejects a staged update`() {
        val store: TrustStore = UnavailableTrustStore
        val staging = TrustBundleCandidateStaging(Memory())
        val candidate = fixture("valid")

        assertEquals(
            TrustResult.Failure(TrustFailure.Unavailable),
            staging.stageAndSubmit(store, candidate),
        )
        assertNull(store.state())
    }

    @Test
    fun `rejected candidate leaves accepted state untouched`() {
        val accepted = Memory()
        val store = openStore(accepted)
        val staging = TrustBundleCandidateStaging(Memory())
        val candidate = fixture("foreign-key")
        staging.stage(candidate)

        val result = staging.submit(store, candidate)

        assertEquals(TrustFailure.InvalidSignature, (result as TrustResult.Failure).reason)
        assertNull(accepted.record)
        assertNull(store.state().bundle)
    }

    @Test
    fun `submitting with nothing staged fails rather than accepting a stale bundle`() {
        val store = openStore()
        val staging = TrustBundleCandidateStaging(Memory())

        val result = staging.submit(store, fixture("valid"))

        assertEquals(TrustFailure.PersistenceFailed, (result as TrustResult.Failure).reason)
    }

    @Test
    fun `submitting an unreadable candidate returns a persistence failure`() {
        val path = Files.createTempFile("trust-corrupt-candidate", ".dat")
        try {
            val store = openStore()
            val staging = TrustBundleCandidateStaging(FileTrustPersistence(path))
            val candidate = fixture("valid")
            staging.stage(candidate)
            Files.write(path, byteArrayOf(1))

            val result = assertIs<TrustResult.Failure>(staging.submit(store, candidate))

            assertEquals(TrustFailure.PersistenceFailed, result.reason)
            assertNull(store.state().version)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `one staging instance cannot submit a replacement written by another`() {
        val directory = Files.createTempDirectory("trust-candidate-race")
        val path = directory.resolve("candidate.dat")
        try {
            val store = openStore()
            val first = TrustBundleCandidateStaging(FileTrustPersistence(path))
            val second = TrustBundleCandidateStaging(FileTrustPersistence(path))
            val valid = fixture("valid")
            val alteredPayload = valid.bytes.apply { this[0] = 0 }
            val alteredSignature =
                valid.signature.apply { this[0] = (this[0].toInt() xor 1).toByte() }
            val candidates =
                listOf(
                    SignedTrustBundle(alteredPayload, valid.signature),
                    SignedTrustBundle(valid.bytes, alteredSignature),
                )

            for (candidate in candidates) {
                first.stage(candidate)
                second.stage(valid)

                val result = first.submit(store, candidate)
                assertEquals(
                    TrustFailure.CandidateChanged,
                    assertIs<TrustResult.Failure>(result).reason,
                )
                assertNull(store.state().version)
            }
            assertEquals(
                42L,
                assertIs<TrustResult.Success<TrustState>>(second.submit(store, valid)).value.version,
            )
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `failed staging write stops submission`() {
        val store = openStore()
        var readAttempted = false
        val staging = TrustBundleCandidateStaging(object : TrustPersistence {
            override fun read(): SignedTrustBundle? {
                readAttempted = true
                return null
            }

            override fun write(bundle: SignedTrustBundle) {
                throw IOException("staging write failed")
            }
        })

        assertEquals(
            TrustResult.Failure(TrustFailure.PersistenceFailed),
            staging.stageAndSubmit(store, fixture("valid")),
        )
        assertFalse(readAttempted)
        assertNull(store.state().version)
    }

    @Test
    fun `candidate and accepted locations are independent`() {
        val accepted = Memory()
        val store = openStore(accepted)
        val candidateBackend = Memory()
        val staging = TrustBundleCandidateStaging(candidateBackend)
        val candidate = fixture("valid")
        staging.stage(candidate)
        staging.submit(store, candidate)

        // the candidate backend still holds what was staged; the store's own backend holds
        // what was accepted. Neither was reused as the other's storage.
        assertNotNull(candidateBackend.record)
        assertNotNull(accepted.record)
    }
}
