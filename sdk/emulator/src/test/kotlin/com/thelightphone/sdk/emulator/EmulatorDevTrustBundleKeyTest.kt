package com.thelightphone.sdk.emulator

import com.thelightphone.sdk.trust.LightTrustBundleFormat
import com.thelightphone.sdk.trust.LightTrustBundleVerifier
import com.thelightphone.sdk.trust.TrustResult
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EmulatorDevTrustBundleKeyTest {
    @Test
    fun startupPinMatchesCheckedInKeyPair() {
        val decoder = Base64.getDecoder()
        fun readPem(name: String): ByteArray =
            decoder.decode(
                File("dev-keys/INSECURE-trust-bundle-$name.pem")
                    .readLines()
                    .filterNot { it.startsWith("-----") }
                    .joinToString("")
            )

        val publicKey = decoder.decode(EMULATOR_DEV_TRUST_BUNDLE_PUBLIC_KEY)
        assertEquals(readPem("public").toList(), publicKey.toList())
        val privateKey =
            KeyFactory.getInstance("Ed25519")
                .generatePrivate(PKCS8EncodedKeySpec(readPem("private")))
        val bundle =
            """{"schemaVersion":1,"version":1,"issuedAt":"2026-11-17T00:00:00Z","allow":[],"block":[],"trustedStampCerts":[],"revokedStampCerts":[]}"""
                .encodeToByteArray()
        val signature =
            Signature.getInstance("Ed25519").run {
                initSign(privateKey)
                update(LightTrustBundleFormat.signedPayloadPrefix)
                update(bundle)
                sign()
            }

        assertIs<TrustResult.Success<*>>(
            LightTrustBundleVerifier(listOf(publicKey)).verify(bundle, signature)
        )
        val foreignKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded
        assertIs<TrustResult.Failure>(
            LightTrustBundleVerifier(listOf(foreignKey)).verify(bundle, signature)
        )
    }
}
