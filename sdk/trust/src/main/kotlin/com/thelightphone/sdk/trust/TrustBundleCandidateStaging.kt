package com.thelightphone.sdk.trust

/**
 * Stages a complete bytes-and-signature pair separately from [LightTrustStore]'s accepted state. A
 * submitter supplies the pair it staged so that another writer replacing the staging file cannot
 * change what is submitted on its behalf.
 *
 * [candidatePersistence] should point at its own file, never the same backing file as the store's
 * own [TrustPersistence] — the two states must stay in distinct locations.
 */
class TrustBundleCandidateStaging(private val candidatePersistence: TrustPersistence) {
    @Synchronized fun stage(candidate: SignedTrustBundle) = candidatePersistence.write(candidate)

    @Synchronized fun candidate(): SignedTrustBundle? = candidatePersistence.read()

    @Synchronized
    fun submit(store: LightTrustStore, expected: SignedTrustBundle): TrustResult<TrustState> {
        val candidate =
            try {
                candidatePersistence.read()
            } catch (_: Exception) {
                return TrustResult.Failure(TrustFailure.PersistenceFailed)
            } ?: return TrustResult.Failure(TrustFailure.PersistenceFailed)
        if (!candidate.hasSameContent(expected)) {
            return TrustResult.Failure(TrustFailure.CandidateChanged)
        }
        return store.accept(candidate)
    }
}
