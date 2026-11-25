package com.thelightphone.sdk.trust

import java.io.IOException

class SignedTrustBundle(bytes: ByteArray, signature: ByteArray) {
    private val payload = bytes.copyOf()
    private val detached = signature.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
    val signature: ByteArray get() = detached.copyOf()

    internal fun hasSameContent(other: SignedTrustBundle): Boolean =
        payload.contentEquals(other.payload) && detached.contentEquals(other.detached)
}

/**
 * Implementations atomically replace the whole record. A failure before replacement leaves the
 * old record intact. The file backend signals an already visible replacement whose durability
 * could not be confirmed with [TrustWriteDurabilityUnknown].
 */
interface TrustPersistence {
    fun read(): SignedTrustBundle?
    fun write(bundle: SignedTrustBundle)
}

internal class TrustWriteDurabilityUnknown(cause: Exception) :
    IOException("Trust bundle was replaced but its durability could not be confirmed", cause)

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
) : TrustStore {
    private val pins = frozenSet(imagePins)
    private var accepted = stateFor(initial)

    @Synchronized override fun state(): TrustState = accepted

    @Synchronized override fun accept(input: SignedTrustBundle): TrustResult<TrustState> {
        val result = verifier.verify(input.bytes, input.signature)
        if (result is TrustResult.Failure) return result

        val bundle = (result as TrustResult.Success).value
        val floor = accepted.version
        if (floor != null && bundle.version <= floor) {
            return TrustResult.Failure(TrustFailure.VersionNotNewer(bundle.version, floor))
        }
        try {
            persistence.write(input)
        } catch (_: TrustWriteDurabilityUnknown) {
            accepted = stateFor(bundle)
            return TrustResult.Failure(TrustFailure.PersistenceDurabilityUnknown(bundle.version))
        } catch (_: Exception) {
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
                } catch (_: TrustWriteDurabilityUnknown) {
                    return TrustResult.Failure(
                        TrustFailure.PersistenceDurabilityUnknown(checkNotNull(initial).version)
                    )
                } catch (_: Exception) {
                    return TrustResult.Failure(TrustFailure.PersistenceFailed)
                }
            }
            return TrustResult.Success(LightTrustStore(verifier, persistence, imagePins, initial))
        }
    }
}
