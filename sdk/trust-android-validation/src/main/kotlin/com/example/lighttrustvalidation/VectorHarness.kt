package com.example.lighttrustvalidation

import android.content.Context
import com.thelightphone.sdk.trust.*
import java.util.Base64

object VectorHarness {
    @JvmStatic
    fun verify(context: Context, fixture: String): String {
        val pem =
            context.assets.open("INSECURE-bundle-public.pem").bufferedReader().use { it.readText() }
        val publicKey =
            Base64.getDecoder()
                .decode(pem.lineSequence().filter { !it.startsWith("---") }.joinToString(""))
        val payload = context.assets.open("$fixture/bundle.json").use { it.readBytes() }
        val signature = context.assets.open("$fixture/bundle.sig").use { it.readBytes() }
        return when (
            val result = LightTrustBundleVerifier(listOf(publicKey)).verify(payload, signature)
        ) {
            is TrustResult.Success -> "accepted:${result.value.version}"
            is TrustResult.Failure ->
                when (result.reason) {
                    TrustFailure.InvalidSignature -> "invalid-signature"
                    is TrustFailure.InvalidJson -> "invalid-json"
                    is TrustFailure.UnsupportedSchema -> "unsupported-schema"
                    else -> "unexpected-rejection"
                }
        }
    }
}
