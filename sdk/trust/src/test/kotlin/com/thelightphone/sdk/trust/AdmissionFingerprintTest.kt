package com.thelightphone.sdk.trust

import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Instant

class AdmissionFingerprintTest {
    private val fingerprint = InstallationFingerprint("/data/app/original/base.apk", 10, 20)
    private val record = AdmissionRecord("example.tool", "a".repeat(64), "b".repeat(64), "example.tool",
        1, 7, LightTrustFilterLevel.AllowLightSignedApks, Instant.fromEpochSeconds(0, 0), installationFingerprint = fingerprint)
    private val allow = AdmissionEvaluation(LightInstallDecision.Allow, record)

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
