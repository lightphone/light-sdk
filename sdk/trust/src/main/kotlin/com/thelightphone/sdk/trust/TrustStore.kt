package com.thelightphone.sdk.trust

interface TrustStore {
    /** Returns the current accepted state, or null if the store is unavailable. */
    fun state(): TrustState?

    fun accept(input: SignedTrustBundle): TrustResult<TrustState>
}

object UnavailableTrustStore : TrustStore {
    override fun state(): TrustState? = null

    override fun accept(input: SignedTrustBundle): TrustResult<TrustState> =
        TrustResult.Failure(TrustFailure.Unavailable)
}
