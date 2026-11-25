package com.thelightphone.sdk.emulator

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmulatorSignerFingerprintTest {
    @Test
    fun developmentPinMatchesCheckedInCertificate() {
        val keystore = KeyStore.getInstance("JKS")
        File("../keys/lightsdk-dev.jks").inputStream().use {
            keystore.load(it, "android".toCharArray())
        }
        val certificate = keystore.getCertificate("lightsdk-dev")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).toHexString()
        assertEquals(fingerprint, LIGHTSDK_DEV_CERT_SHA256)
    }

    @Test
    fun inboxHashesUseCanonicalHex() {
        assertEquals(LIGHTSDK_DEV_CERT_SHA256, canonicalSignerFingerprint(LIGHTSDK_DEV_CERT_SHA256))
        assertEquals(LIGHTSDK_DEV_CERT_SHA256, canonicalSignerFingerprint("  ${LIGHTSDK_DEV_CERT_SHA256.uppercase()}  "))
        assertNull(canonicalSignerFingerprint("a".repeat(63)))
        assertNull(canonicalSignerFingerprint("g".repeat(64)))
    }
}
