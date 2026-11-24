package com.thelightphone.sdk.emulator.http

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class TrustBundleUploadTest {
    @Test fun decodesExactPair() {
        val candidate = parseTrustBundleUpload("""{"bytesBase64":"AAE=","signatureBase64":"AgM="}""")
        assertContentEquals(byteArrayOf(0, 1), candidate.bytes)
        assertContentEquals(byteArrayOf(2, 3), candidate.signature)
    }

    @Test fun rejectsMissingNullAndWrongTypes() {
        for (field in listOf("bytesBase64", "signatureBase64")) {
            val other = if (field == "bytesBase64") "signatureBase64" else "bytesBase64"
            assertFailsWith<IllegalArgumentException> {
                parseTrustBundleUpload("""{"$other":"AA=="}""")
            }
            for (invalid in listOf("null", "{}", "[]", "true", "42")) {
                assertFailsWith<IllegalArgumentException> {
                    parseTrustBundleUpload("""{"$other":"AA==","$field":$invalid}""")
                }
            }
        }
    }

    @Test fun rejectsInvalidJsonAndBase64() {
        for (request in listOf("{", "[]", """{"bytesBase64":"!","signatureBase64":"AA=="}""")) {
            assertFailsWith<IllegalArgumentException> { parseTrustBundleUpload(request) }
        }
    }
}
