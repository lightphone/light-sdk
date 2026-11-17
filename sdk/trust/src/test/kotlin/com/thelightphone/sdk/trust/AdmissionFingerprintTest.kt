package com.thelightphone.sdk.trust

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.*

class AdmissionFingerprintTest {
    private val fingerprint = InstallationFingerprint("/data/app/original/base.apk", 10, 20)
    private val record = AdmissionRecord("example.tool", "a".repeat(64), "b".repeat(64), "example.tool",
        1, 7, LightTrustFilterLevel.AllowLightSignedApks, Instant.EPOCH, installationFingerprint = fingerprint)
    private val allow = AdmissionEvaluation(LightInstallDecision.Allow, record)

    @Test fun `legacy file is inspection evidence only until fresh admission writes version two`() {
        val directory = Files.createTempDirectory("legacy-admission-")
        val file = directory.resolve("records")
        try {
            // Encode the actual LAD1 layout, independently of the LAD2 writer.
            val payload = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use {
                    it.writeInt(0x4c414431); it.writeInt(1)
                    it.writeUTF(record.packageName); it.writeUTF(record.apkSha256); it.writeUTF(record.signerSha256)
                    it.writeBoolean(true); it.writeUTF(record.toolId!!)
                    it.writeLong(record.versionCode); it.writeBoolean(true); it.writeLong(7)
                    it.writeUTF(record.admittedUnderLevel.name)
                    it.writeLong(0); it.writeInt(0); it.writeBoolean(false)
                }
            }.toByteArray()
            Files.write(file, MessageDigest.getInstance("SHA-256").digest(payload) + payload)
            val backend = FileAdmissionPersistence(file)
            val store = AdmissionStore(backend)
            assertNull(store.admissionOf(record.packageName)?.installationFingerprint)
            assertFalse(store.hasAdmissionRecord(record.packageName, record.apkSha256, fingerprint))
            val deny = LightInstallDecision.Deny(DenyReason.UntrustedStampCert)
            assertEquals(AdmissionOutcome.Refused(deny), store.admitInstalled(record.packageName, record.apkSha256, fingerprint) {
                AdmissionEvaluation(deny, null)
            })
            assertNull(backend.read().single().installationFingerprint)
            assertIs<AdmissionOutcome.Admitted>(store.admitInstalled(record.packageName, record.apkSha256, fingerprint) { allow })
            assertEquals(record, AdmissionStore(backend).admissionOf(record.packageName))
            assertTrue(AdmissionStore(backend).hasAdmissionRecord(record.packageName, record.apkSha256, fingerprint))
            assertEquals(0x4c414432, java.nio.ByteBuffer.wrap(Files.readAllBytes(file), 32, 4).int)
        } finally {
            Files.deleteIfExists(file); Files.deleteIfExists(directory)
        }
    }

    @Test fun `same hash and timestamps at a new location require fresh admission after reopen`() {
        val directory = Files.createTempDirectory("fingerprint-admission-")
        val file = directory.resolve("records")
        try {
            val backend = FileAdmissionPersistence(file)
            AdmissionStore(backend).recordSuccessfulInstall(allow)
            val reopened = AdmissionStore(backend)
            val reinstalled = fingerprint.copy(baseApkPath = "/data/app/reinstalled/base.apk")
            assertFalse(reopened.hasAdmissionRecord(record.packageName, record.apkSha256, reinstalled))
            val deny = LightInstallDecision.Deny(DenyReason.UntrustedStampCert)
            assertEquals(AdmissionOutcome.Refused(deny), reopened.admitInstalled(record.packageName, record.apkSha256, reinstalled) {
                AdmissionEvaluation(deny, null)
            })
            assertEquals(fingerprint, backend.read().single().installationFingerprint)
            assertIs<AdmissionOutcome.Admitted>(reopened.admitInstalled(record.packageName, record.apkSha256, fingerprint) {
                error("unchanged persisted evidence must survive reopen")
            })
        } finally {
            Files.deleteIfExists(file); Files.deleteIfExists(directory)
        }
    }

    @Test fun `missing fingerprint never creates or matches admission`() {
        val backend = object : AdmissionPersistence {
            override fun read() = listOf(record)
            override fun write(records: List<AdmissionRecord>) = error("must not write")
        }
        val store = AdmissionStore(backend)
        assertFalse(store.hasAdmissionRecord(record.packageName, record.apkSha256))
        assertEquals(AdmissionOutcome.EvaluationFailed, store.admitInstalled(record.packageName, record.apkSha256) {
            error("missing installation evidence")
        })
        assertEquals(AdmissionOutcome.EvaluationFailed, store.recordSuccessfulInstall(
            allow.copy(record = record.copy(installationFingerprint = null))))
    }

    @Test fun `fallback cannot attach captured approval to a different installation fingerprint`() {
        val store = AdmissionStore(object : AdmissionPersistence {
            override fun read() = emptyList<AdmissionRecord>()
            override fun write(records: List<AdmissionRecord>) = error("must not write")
        })
        assertFailsWith<IllegalArgumentException> {
            store.admitInstalled(record.packageName, record.apkSha256, fingerprint.copy(lastUpdateTimeMillis = 21)) { allow }
        }
    }
}
