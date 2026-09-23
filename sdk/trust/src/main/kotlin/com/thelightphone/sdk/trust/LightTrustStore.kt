package com.thelightphone.sdk.trust

class SignedTrustBundle(bytes: ByteArray, signature: ByteArray) {
    private val payload = bytes.copyOf()
    private val detached = signature.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
    val signature: ByteArray get() = detached.copyOf()
}

/**
 * Implementations must replace the whole record in one atomic step, or fail
 * without changing what is stored. A partial write would leave a bundle and its
 * version floor disagreeing.
 */
interface TrustPersistence {
    fun read(): SignedTrustBundle?
    fun write(bundle: SignedTrustBundle)
}

data class TrustState(val bundle: LightTrustBundle?, val trustedStampCerts: Set<String>) {
    val version: Long? get() = bundle?.version
}

/**
 * Create only one LightTrustStore per persistence backend. Each instance tracks
 * the last accepted version in memory. Two instances sharing storage could
 * overwrite a newer bundle using an outdated version check.
 *
 * The persisted bundle's signed version field is also the version floor,
 * keeping the bundle and its floor together in one atomic write.
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
            var winner: SignedTrustBundle? = null
            for (record in listOfNotNull(imageBundle, disk)) {
                when (val result = verifier.verify(record.bytes, record.signature)) {
                    is TrustResult.Failure -> return result
                    is TrustResult.Success -> {
                        val floor = initial?.version
                        if (floor == null || result.value.version > floor) {
                            initial = result.value
                            winner = record
                        }
                    }
                }
            }
            if (winner != null && winner !== disk &&
                (disk == null || !winner.bytes.contentEquals(disk.bytes) || !winner.signature.contentEquals(disk.signature))) {
                try {
                    persistence.write(winner)
                } catch (_: Exception) {
                    return TrustResult.Failure(TrustFailure.PersistenceFailed)
                }
            }
            return TrustResult.Success(LightTrustStore(verifier, persistence, imagePins, initial))
        }
    }
}
