package com.thelightphone.sdk.emulator.http

import com.thelightphone.sdk.trust.SignedTrustBundle
import com.thelightphone.sdk.trust.TrustBundleCandidateStaging
import com.thelightphone.sdk.trust.TrustFailure
import com.thelightphone.sdk.trust.TrustPersistence
import com.thelightphone.sdk.trust.TrustResult
import com.thelightphone.sdk.trust.TrustState
import com.thelightphone.sdk.trust.TrustStore
import com.thelightphone.sdk.trust.UnavailableTrustStore
import kotlin.test.Test
import kotlin.test.assertEquals

class TrustBundleSubmissionTest {
    @Test
    fun publishedPoliciesNotifyBeforeReturning() {
        verify(TrustResult.Success(TrustState(null, emptySet())), expectedNotifications = 1)
        verify(TrustResult.Failure(TrustFailure.PersistenceDurabilityUnknown(2)), expectedNotifications = 1)
    }

    @Test
    fun rejectedAndUnavailableStoresDoNotNotify() {
        verify(TrustResult.Failure(TrustFailure.PersistenceFailed), expectedNotifications = 0)
        val unavailable = TrustResult.Failure(TrustFailure.Unavailable)
        verify(unavailable, expectedNotifications = 0, store = UnavailableTrustStore)
    }

    private fun verify(
        result: TrustResult<TrustState>,
        expectedNotifications: Int,
        store: TrustStore = object : TrustStore {
            override fun state(): TrustState? = null
            override fun accept(input: SignedTrustBundle) = result
        },
    ) {
        val persistence = object : TrustPersistence {
            private var candidate: SignedTrustBundle? = null
            override fun read() = candidate
            override fun write(bundle: SignedTrustBundle) {
                candidate = bundle
            }
        }
        var notifications = 0
        val actual = submitTrustBundle(
            TrustBundleCandidateStaging(persistence),
            store,
            SignedTrustBundle(byteArrayOf(1), byteArrayOf(2)),
        ) { notifications++ }

        assertEquals(result, actual)
        assertEquals(expectedNotifications, notifications)
    }
}
