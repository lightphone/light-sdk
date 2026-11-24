import com.thelightphone.sdk.server.installer.TrustDevReload
import com.thelightphone.sdk.trust.FileTrustPersistence
import com.thelightphone.sdk.trust.TrustFailure
import com.thelightphone.sdk.trust.LightTrustBundleFormat
import com.thelightphone.sdk.trust.LightTrustBundleVerifier
import com.thelightphone.sdk.trust.LightTrustStore
import com.thelightphone.sdk.trust.SignedTrustBundle
import com.thelightphone.sdk.trust.TrustBundleCandidateStaging
import com.thelightphone.sdk.trust.TrustPersistence
import com.thelightphone.sdk.trust.TrustResult
import java.io.IOException
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.*

class TrustDevReloadTest {
    private class Memory : TrustPersistence {
        var record: SignedTrustBundle? = null
        override fun read() = record
        override fun write(bundle: SignedTrustBundle) { record = bundle }
    }

    // Generated fresh per test run rather than loaded from a fixture, so this stays
    // self-contained in :sdk:server (no resource path shared with :sdk:trust's tests).
    private fun selfSignedBundle(): Pair<LightTrustBundleVerifier, SignedTrustBundle> {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = """{"schemaVersion":1,"version":1,"issuedAt":"2026-11-17T00:00:00Z","allow":[],"block":[],"trustedStampCerts":[],"revokedStampCerts":[]}""".encodeToByteArray()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(LightTrustBundleFormat.signedPayloadPrefix)
        signer.update(payload)
        val verifier = LightTrustBundleVerifier(listOf(keyPair.public.encoded))
        return verifier to SignedTrustBundle(payload, signer.sign())
    }

    private fun openStore(verifier: LightTrustBundleVerifier) =
        (LightTrustStore.open(verifier, Memory(), emptySet()) as TrustResult.Success).value

    @Test fun `no candidate when either file is missing`() {
        val directory = Files.createTempDirectory("dev-reload-test").toFile()
        assertNull(TrustDevReload.readCandidate(directory))

        directory.resolve("bundle.json").writeBytes(byteArrayOf(1))
        assertNull(TrustDevReload.readCandidate(directory))
    }

    @Test fun `reads the exact bytes dropped at the candidate directory`() {
        val directory = Files.createTempDirectory("dev-reload-test").toFile()
        val (_, bundle) = selfSignedBundle()
        directory.resolve("bundle.json").writeBytes(bundle.bytes)
        directory.resolve("bundle.sig").writeBytes(bundle.signature)

        val read = TrustDevReload.readCandidate(directory)

        assertNotNull(read)
        assertContentEquals(bundle.bytes, read.bytes)
        assertContentEquals(bundle.signature, read.signature)
    }

    @Test fun `reload reports store unavailable before looking for a candidate`() {
        val outcome = TrustDevReload.reload(
            Files.createTempDirectory("dev-reload-test").toFile(),
            TrustBundleCandidateStaging(Memory()),
            store = null,
        )
        assertEquals(TrustDevReload.Outcome.StoreUnavailable, outcome)
    }

    @Test fun `reload reports no candidate when the store is available but files are absent`() {
        val (verifier, _) = selfSignedBundle()
        val outcome = TrustDevReload.reload(
            Files.createTempDirectory("dev-reload-test").toFile(),
            TrustBundleCandidateStaging(Memory()),
            store = openStore(verifier),
        )
        assertEquals(TrustDevReload.Outcome.NoCandidate, outcome)
    }

    @Test fun `reload stages and submits a dropped candidate into the store`() {
        val directory = Files.createTempDirectory("dev-reload-test").toFile()
        val (verifier, bundle) = selfSignedBundle()
        directory.resolve("bundle.json").writeBytes(bundle.bytes)
        directory.resolve("bundle.sig").writeBytes(bundle.signature)
        val store = openStore(verifier)

        val outcome = TrustDevReload.reload(directory, TrustBundleCandidateStaging(Memory()), store)

        val applied = assertIs<TrustDevReload.Outcome.Applied>(outcome)
        assertIs<TrustResult.Success<*>>(applied.result)
        assertEquals(1L, store.state().version)
    }

    @Test fun `reload reports oversized files without applying a candidate`() {
        val directory = Files.createTempDirectory("dev-reload-test").toFile()
        val (verifier, bundle) = selfSignedBundle()
        directory.resolve("bundle.json").writeBytes(bundle.bytes)
        directory.resolve("bundle.sig").writeBytes(ByteArray(FileTrustPersistence.MAX_SIGNATURE_BYTES + 1))
        val store = openStore(verifier)

        val outcome = TrustDevReload.reload(directory, TrustBundleCandidateStaging(Memory()), store)

        assertIs<TrustDevReload.Outcome.ReadFailed>(outcome)
        assertNull(store.state().version)
    }

    @Test fun `reload returns a failure when staging cannot write`() {
        val directory = Files.createTempDirectory("dev-reload-test").toFile()
        val (verifier, bundle) = selfSignedBundle()
        directory.resolve("bundle.json").writeBytes(bundle.bytes)
        directory.resolve("bundle.sig").writeBytes(bundle.signature)
        val store = openStore(verifier)
        var readAttempted = false
        val staging = TrustBundleCandidateStaging(object : TrustPersistence {
            override fun read(): SignedTrustBundle? {
                readAttempted = true
                return null
            }
            override fun write(bundle: SignedTrustBundle) { throw IOException("staging write failed") }
        })

        val outcome = TrustDevReload.reload(directory, staging, store)

        val failure = assertIs<TrustDevReload.Outcome.Applied>(outcome)
        assertEquals(TrustResult.Failure(TrustFailure.PersistenceFailed), failure.result)
        assertFalse(readAttempted)
        assertNull(store.state().version)
    }
}
