package com.thelightphone.sdk.trust

/**
 * Stages a complete bytes+signature pair at a location distinct from [LightTrustStore]'s accepted
 * state, so a partial write (process death mid-transfer, a crashed HTTP upload) can never be
 * mistaken for, or silently merged into, accepted state. [submit] only ever calls
 * [LightTrustStore.accept] with a candidate that was fully staged first; a rejected candidate
 * leaves accepted state untouched, per [LightTrustStore.accept]'s own contract.
 *
 * [candidatePersistence] should point at its own file, never the same backing file as the store's
 * own [TrustPersistence] — the two states must stay in distinct locations.
 */
class TrustBundleCandidateStaging(private val candidatePersistence: TrustPersistence) {
    @Synchronized fun stage(candidate: SignedTrustBundle) = candidatePersistence.write(candidate)

    @Synchronized fun candidate(): SignedTrustBundle? = candidatePersistence.read()

    @Synchronized
    fun submit(store: LightTrustStore): TrustResult<TrustState> {
        val candidate =
            candidatePersistence.read()
                ?: return TrustResult.Failure(TrustFailure.PersistenceFailed)
        return store.accept(candidate)
    }
}
