package com.thelightphone.sdk.trust

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals

class StampVerifierTest {
    @Test
    fun missingApkHasNoStamp() {
        assertIs<StampResult.NotPresent>(ApkSigStampVerifier().verify(listOf("missing.apk")))
    }

    @Test
    fun splitApksArePlatformOnly() {
        assertIs<StampResult.Unavailable>(ApkSigStampVerifier().verify(listOf("base.apk", "split.apk")))
    }

    @Test
    fun signerProducedApkVerifiesWhenProvided() {
        val apk = System.getProperty("lightStampedApk") ?: return
        val expected = System.getProperty("lightStampCertSha256") ?: error("missing stamp certificate digest")
        val result = assertIs<StampResult.Verified>(ApkSigStampVerifier().verify(listOf(apk)))
        assertEquals(expected, result.certSha256)
    }
}
