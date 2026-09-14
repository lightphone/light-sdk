package com.thelightphone.sdk.trust

class SignedTrustBundle(bytes: ByteArray, signature: ByteArray) {
    private val payload = bytes.copyOf()
    private val detached = signature.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
    val signature: ByteArray get() = detached.copyOf()
}

/** Implementations atomically replace the whole record, or throw without changing it. */
interface TrustPersistence {
    fun read(): SignedTrustBundle?
    fun write(bundle: SignedTrustBundle)
}

data class TrustState(val bundle: LightTrustBundle?, val trustedStampCerts: Set<String>) {
    val version: Long? get() = bundle?.version
}

/**
 * A single store instance must own its persistence backend; two instances over one backend race
 * their floors. The floor is not stored separately — it is the version field of the persisted
 * signed bundle, so it cannot drift from the bundle it guards and cannot be moved without a
 * valid signature.
 */
class LightTrustStore private constructor(
    private val verifier: LightTrustBundleVerifier,
    private val persistence: TrustPersistence,
    imagePins: Set<String>,
    initial: LightTrustBundle?,
) {
    private val pins = frozenSet(imagePins)
    private var accepted = stateFor(initial)

    @Synchronized fun state(): TrustState = accepted

    @Synchronized fun accept(input: SignedTrustBundle): TrustResult<TrustState> {
        val result = verifier.verify(input.bytes, input.signature)
        if (result is TrustResult.Failure) return result

        val bundle = (result as TrustResult.Success).value
        val floor = accepted.version
        if (floor != null && bundle.version <= floor) {
            return TrustResult.Failure(TrustFailure.VersionNotNewer(bundle.version, floor))
        }
        try { persistence.write(input) } catch (_: Exception) {
            return TrustResult.Failure(TrustFailure.PersistenceFailed)
        }
        accepted = stateFor(bundle)
        return TrustResult.Success(accepted)
    }

    private fun stateFor(bundle: LightTrustBundle?): TrustState = TrustState(bundle,
        frozenSet((pins + (bundle?.trustedStampCerts ?: emptySet())) - (bundle?.revokedStampCerts ?: emptySet())))

    companion object {
        fun open(
            verifier: LightTrustBundleVerifier,
            persistence: TrustPersistence,
            imagePins: Set<String>,
            imageBundle: SignedTrustBundle? = null,
        ): TrustResult<LightTrustStore> {
            if (imagePins.any { !Regex("[0-9a-f]{64}").matches(it) }) {
                return TrustResult.Failure(TrustFailure.InvalidField("imagePins"))
            }
            val disk = try { persistence.read() } catch (_: Exception) {
                return TrustResult.Failure(TrustFailure.PersistenceFailed)
            }
            var initial: LightTrustBundle? = null
            for (record in listOfNotNull(imageBundle, disk)) {
                when (val result = verifier.verify(record.bytes, record.signature)) {
                    is TrustResult.Failure -> return result
                    is TrustResult.Success -> {
                        val floor = initial?.version
                        if (floor != null && result.value.version < floor) {
                            return TrustResult.Failure(TrustFailure.VersionNotNewer(result.value.version, floor))
                        }
                        initial = result.value
                    }
                }
            }
            if (disk == null && imageBundle != null) {
                try { persistence.write(imageBundle) } catch (_: Exception) {
                    return TrustResult.Failure(TrustFailure.PersistenceFailed)
                }
            }
            return TrustResult.Success(LightTrustStore(verifier, persistence, imagePins, initial))
        }
    }
}
