package com.thelightphone.sdk.trust

import java.util.Base64
import kotlin.test.*

/**
 * Proves the constant embedded in EmulatorApplication.kt
 * (EMULATOR_DEV_TRUST_BUNDLE_PUBLIC_KEY) actually verifies a signature made with
 * its corresponding private key (sdk/emulator/dev-keys/INSECURE-trust-bundle-private.pem),
 * so a copy-paste mistake in that constant doesn't go unnoticed until someone tries it
 * against a real emulator.
 */
class EmulatorDevTrustBundleKeyTest {
    // Must match EMULATOR_DEV_TRUST_BUNDLE_PUBLIC_KEY in EmulatorApplication.kt exactly.
    private val publicKey = Base64.getDecoder().decode("MCowBQYDK2VwAyEAzOvW4GDLmhLQuFHyo+r6xrvQGxyAyZgabdwOAg3PSBc=")

    // Payload and detached signature produced with
    // sdk/emulator/dev-keys/INSECURE-trust-bundle-private.pem, per that directory's README.
    private val payload = """{"schemaVersion":1,"version":1,"issuedAt":"2026-11-17T00:00:00Z","allow":[],"block":[],"trustedStampCerts":[],"revokedStampCerts":[]}""".encodeToByteArray()
    private val signature = Base64.getDecoder()
        .decode("PGji4gLkIMg5vc7W9pJs1JNEatZrsLIs7sk8Y7jmgwdFdx7TaDLSaxyw+XcTCsIQbHJx0p5zAWUNPMViSijuDw==")

    @Test fun `embedded emulator dev public key verifies a bundle signed with its private key`() {
        val result = LightTrustBundleVerifier(listOf(publicKey)).verify(payload, signature)
        assertIs<TrustResult.Success<LightTrustBundle>>(result)
        assertEquals(1L, result.value.version)
    }

    @Test fun `the same signature fails against an unrelated key`() {
        val foreignKey = Base64.getDecoder().decode(
            checkNotNull(javaClass.getResourceAsStream("/bundle/INSECURE-foreign-public.pem"))
                .use { it.readBytes() }
                .decodeToString()
                .lines()
                .filterNot { it.startsWith("---") }
                .joinToString("")
        )
        val result = LightTrustBundleVerifier(listOf(foreignKey)).verify(payload, signature)
        assertIs<TrustResult.Failure>(result)
    }
}
