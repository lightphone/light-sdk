package com.thelightphone.sdk.trust

import kotlin.test.Test
import kotlin.test.assertContentEquals

class LightTrustBundleFormatTest {
    @Test
    fun `loads shared domain separator`() {
        assertContentEquals(
            "lightos-trust-bundle-v1\u0000".encodeToByteArray(),
            LightTrustBundleFormat.signedPayloadPrefix,
        )
    }
}
