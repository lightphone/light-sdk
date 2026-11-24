package com.thelightphone.sdk.trust

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
        staging.stage(fixture("valid"))

        val result = staging.submit(store)

        assertIs<TrustResult.Success<TrustState>>(result)
        assertNotNull(accepted.record)
        assertEquals(result.value, store.state())
    }

    @Test
    fun `rejected candidate leaves accepted state untouched`() {
        val accepted = Memory()
        val store = openStore(accepted)
        val staging = TrustBundleCandidateStaging(Memory())
        staging.stage(fixture("foreign-key"))

        val result = staging.submit(store)

        assertEquals(TrustFailure.InvalidSignature, (result as TrustResult.Failure).reason)
        assertNull(accepted.record)
        assertNull(store.state().bundle)
    }

    @Test
    fun `submitting with nothing staged fails rather than accepting a stale bundle`() {
        val store = openStore()
        val staging = TrustBundleCandidateStaging(Memory())

        val result = staging.submit(store)

        assertEquals(TrustFailure.PersistenceFailed, (result as TrustResult.Failure).reason)
    }

    @Test
    fun `candidate and accepted locations are independent`() {
        val accepted = Memory()
        val store = openStore(accepted)
        val candidateBackend = Memory()
        val staging = TrustBundleCandidateStaging(candidateBackend)
        staging.stage(fixture("valid"))
        staging.submit(store)

        // the candidate backend still holds what was staged; the store's own backend holds
        // what was accepted. Neither was reused as the other's storage.
        assertNotNull(candidateBackend.record)
        assertNotNull(accepted.record)
    }
}
