package com.thelightphone.sdk.trust

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.IOException

// Pinned public keys are encoded as DER SubjectPublicKeyInfo
class LightTrustBundleVerifier(pinnedKeys: List<ByteArray>) {
    private val pins = pinnedKeys.map { it.copyOf() }

    fun verify(bytes: ByteArray, signature: ByteArray): TrustResult<LightTrustBundle> {
        val payload = bytes.copyOf()
        val detached = signature.copyOf()
        if (pins.isEmpty()) {
            return TrustResult.Failure(TrustFailure.InvalidKey)
        }

        val keys = try {
            pins.map {
                parsePin(it) ?: return TrustResult.Failure(TrustFailure.InvalidKey)
            }
        } catch (_: IOException) {
            return TrustResult.Failure(TrustFailure.InvalidKey)
        } catch (_: IllegalArgumentException) {
            return TrustResult.Failure(TrustFailure.InvalidKey)
        }

        if (detached.size != 64) return TrustResult.Failure(TrustFailure.InvalidSignature)
        val prefix = LightTrustBundleFormat.signedPayloadPrefix
        val verified = keys.any { key ->
            val verifier = Ed25519Signer()
            verifier.init(false, key)
            verifier.update(prefix, 0, prefix.size)
            verifier.update(payload, 0, payload.size)
            verifier.verifySignature(detached)
        }
        if (!verified) return TrustResult.Failure(TrustFailure.InvalidSignature)
        return LightTrustBundleParser.parse(payload)
    }

    private fun parsePin(bytes: ByteArray): Ed25519PublicKeyParameters? {
        val info = SubjectPublicKeyInfo.getInstance(ASN1Primitive.fromByteArray(bytes)) ?: return null
        if (info.algorithm.algorithm != ED25519_OID || info.algorithm.parameters != null ||
            !info.getEncoded(ASN1Encoding.DER).contentEquals(bytes) ||
            info.publicKeyData.padBits != 0
        ) {
            return null
        }

        val raw = info.publicKeyData.octets
        if (raw.size != Ed25519PublicKeyParameters.KEY_SIZE) return null
        return Ed25519PublicKeyParameters(raw)
    }

    companion object {
        private val ED25519_OID = ASN1ObjectIdentifier("1.3.101.112")
    }
}
