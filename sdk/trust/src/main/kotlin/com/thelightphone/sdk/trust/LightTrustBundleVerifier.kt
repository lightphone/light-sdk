package com.thelightphone.sdk.trust

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

// pinnedKeys are the raw binary forms of public keys (DER SubjectPublicKeyInfo), not PEM text.
// They are the trust root: they must come from an image-owned resource, never from the network
// and never from the bundle being verified.
class LightTrustBundleVerifier(pinnedKeys: List<ByteArray>) {
    private val pins = pinnedKeys.map { it.copyOf() }

    fun verify(bytes: ByteArray, signature: ByteArray): TrustResult<LightTrustBundle> {
        val payload = bytes.copyOf()
        val detached = signature.copyOf()
        val factory = try
            { KeyFactory.getInstance("Ed25519")
        } catch (_: GeneralSecurityException) {
            return TrustResult.Failure(TrustFailure.CryptoUnavailable)
        }
        if (pins.isEmpty()) {
            return TrustResult.Failure(TrustFailure.InvalidKey)
        }
        val keys = try {
            pins.map {
                if (it.size != 44 || !it.copyOfRange(0, 12).contentEquals(SPKI_PREFIX)) {
                    return TrustResult.Failure(TrustFailure.InvalidKey)
                }
                factory.generatePublic(X509EncodedKeySpec(it))
            }
        } catch (_: GeneralSecurityException) {
            return TrustResult.Failure(TrustFailure.InvalidKey)
        }
        val verifier = try {
            Signature.getInstance("Ed25519")
        } catch (_: GeneralSecurityException) {
            return TrustResult.Failure(TrustFailure.CryptoUnavailable)
        }
        val verified = keys.any { key ->
            try {
                verifier.initVerify(key)
                verifier.update(LightTrustBundleFormat.signedPayloadPrefix.copyOf())
                verifier.update(payload)
                verifier.verify(detached)
            } catch (_: GeneralSecurityException) {
                false
            }
        }
        if (!verified) {
            return TrustResult.Failure(TrustFailure.InvalidSignature)
        }
        return LightTrustBundleParser.parse(payload)
    }

    companion object {
        private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    }
}
