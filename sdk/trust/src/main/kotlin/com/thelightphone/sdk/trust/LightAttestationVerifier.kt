package com.thelightphone.sdk.trust

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object LightAttestationVerifier {
    fun verify(payload: ByteArray, signature: ByteArray, publicKeyPem: String): Boolean {
        val encodedKey = publicKeyPem
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .let(Base64.getDecoder()::decode)
        val publicKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(encodedKey))
        return Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(payload)
            verify(signature)
        }
    }
}
