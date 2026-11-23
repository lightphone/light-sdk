package com.thelightphone.sdk.trust

import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Instant

class AdmissionStoreTest {
    private val fingerprint = InstallationFingerprint("/data/app/example/base.apk", 1, 1)
    @Test fun `confirmed uninstall removes admission durably and repeated removal is harmless`() {
        val backend = Memory()
        val store = AdmissionStore(backend)
        val artifact = record()
        store.recordSuccessfulInstall(AdmissionEvaluation(LightInstallDecision.Allow, artifact))
        repeat(2) { assertEquals(AdmissionRemovalOutcome.Removed, store.removeAdmission(artifact.packageName)) }
        assertFalse(AdmissionStore(backend).hasAdmissionRecord(artifact.packageName, artifact.apkSha256, fingerprint))
        val denied = LightInstallDecision.Deny(DenyReason.UntrustedStampCert)
        assertEquals(AdmissionOutcome.Refused(denied), store.admitInstalled(artifact.packageName, artifact.apkSha256, fingerprint) {
            AdmissionEvaluation(denied, null)
        })
    }

    @Test fun `failed removal closes admission authority until durable state is reopened`() {
        val backend = Memory()
        val store = AdmissionStore(backend)
        val artifact = record()
        store.recordSuccessfulInstall(AdmissionEvaluation(LightInstallDecision.Allow, artifact))
        backend.fail = true
        assertEquals(AdmissionRemovalOutcome.PersistenceFailed, store.removeAdmission(artifact.packageName))
        assertFalse(store.hasAdmissionRecord(artifact.packageName, artifact.apkSha256, fingerprint))
        assertEquals(AdmissionOutcome.PersistenceFailed, store.admitInstalled(artifact.packageName, artifact.apkSha256, fingerprint) {
            error("must not reauthorize after failed removal")
        })
    }
    private fun record(hash: String = "a".repeat(64)) = AdmissionRecord(
        "example.tool", hash, "b".repeat(64), "example.tool", 1, 7,
        LightTrustFilterLevel.AllowLightSignedApks, Instant.parse("2026-11-12T00:00:00Z"),
        installationFingerprint = fingerprint,
    )

    private class Memory : AdmissionPersistence {
        var records = emptyList<AdmissionRecord>()
        var fail = false
        override fun read() = records
        override fun write(records: List<AdmissionRecord>) {
            if (fail) error("disk failure")
            this.records = records
        }
    }

    @Test fun `lost install write and migration share idempotent fallback`() {
        val backend = Memory()
        val store = AdmissionStore(backend)
        val artifact = record()
        var evaluations = 0
        val evaluate = { evaluations++; AdmissionEvaluation(LightInstallDecision.Allow, artifact) }
        repeat(2) {
            assertIs<AdmissionOutcome.Admitted>(store.admitInstalled(artifact.packageName, artifact.apkSha256, fingerprint, evaluate))
        }
        assertEquals(1, evaluations)
        assertTrue(AdmissionStore(backend).hasAdmissionRecord(artifact.packageName, artifact.apkSha256, fingerprint))
        assertEquals(artifact, store.admissionOf(artifact.packageName))
    }

    @Test fun `revoked stamp after crash refuses unrecorded artifact`() {
        val store = AdmissionStore(Memory())
        val artifact = record()
        val deny = LightInstallDecision.Deny(DenyReason.BadAttestation)
        assertEquals(AdmissionOutcome.Refused(deny), store.admitInstalled(artifact.packageName, artifact.apkSha256, fingerprint) {
            AdmissionEvaluation(deny, null)
        })
        assertFalse(store.hasAdmissionRecord(artifact.packageName, artifact.apkSha256, fingerprint))
    }

    @Test fun `changed bytes require fresh evaluation despite same signer and version`() {
        val store = AdmissionStore(Memory())
        val old = record()
        store.recordSuccessfulInstall(AdmissionEvaluation(LightInstallDecision.Allow, old))
        val next = record("c".repeat(64))
        assertFalse(store.hasAdmissionRecord(next.packageName, next.apkSha256, fingerprint))
        val kill = LightInstallDecision.Kill(BlockAction.Block, "blocked")
        assertEquals(AdmissionOutcome.Refused(kill), store.admitInstalled(next.packageName, next.apkSha256, fingerprint) {
            AdmissionEvaluation(kill, null)
        })
    }

    @Test fun `write failure closes store until reopened`() {
        val backend = Memory()
        val store = AdmissionStore(backend)
        val artifact = record()
        backend.fail = true
        assertEquals(AdmissionOutcome.PersistenceFailed,
            store.recordSuccessfulInstall(AdmissionEvaluation(LightInstallDecision.Allow, artifact)))
        assertFalse(store.hasAdmissionRecord(artifact.packageName, artifact.apkSha256, fingerprint))
        assertEquals(AdmissionOutcome.PersistenceFailed, store.admitInstalled(artifact.packageName, artifact.apkSha256, fingerprint) {
            error("must not evaluate against uncertain storage")
        })
    }

    @Test fun `fallback cannot record different bytes`() {
        val store = AdmissionStore(Memory())
        assertFailsWith<IllegalArgumentException> {
            store.admitInstalled("example.tool", "c".repeat(64), fingerprint) {
                AdmissionEvaluation(LightInstallDecision.Allow, record())
            }
        }
    }

    @Test fun `failed replacement preserves prior durable record on reopen`() {
        val backend = Memory()
        val store = AdmissionStore(backend)
        val old = record()
        store.recordSuccessfulInstall(AdmissionEvaluation(LightInstallDecision.Allow, old))
        backend.fail = true
        val next = record("c".repeat(64))
        assertEquals(AdmissionOutcome.PersistenceFailed,
            store.recordSuccessfulInstall(AdmissionEvaluation(LightInstallDecision.Allow, next)))
        val reopened = AdmissionStore(backend)
        assertTrue(reopened.hasAdmissionRecord(old.packageName, old.apkSha256, fingerprint))
        assertFalse(reopened.hasAdmissionRecord(next.packageName, next.apkSha256, fingerprint))
    }

    @Test fun `artifact read failure cannot create admission`() {
        val store = AdmissionStore(Memory())
        assertEquals(AdmissionOutcome.EvaluationFailed, store.admitInstalled("example.tool", "a".repeat(64), fingerprint) {
            throw java.io.IOException("APK unavailable")
        })
        assertNull(store.admissionOf("example.tool"))
    }

    @Test fun `file round trip preserves bypass provenance and rejects corrupt evidence`() {
        val directory = Files.createTempDirectory("admission-test")
        val path = directory.resolve("records")
        try {
            val backend = FileAdmissionPersistence(path)
            assertTrue(backend.read().isEmpty())
            val bypass = record().copy(toolId = null, exception = AdmissionException.DeveloperHash)
            backend.write(listOf(bypass))
            assertEquals(listOf(bypass), backend.read())
            assertFails { backend.write(listOf(bypass, bypass)) }
            assertEquals(listOf(bypass), backend.read())
            val bytes = Files.readAllBytes(path)
            val corrupt = bytes.copyOf()
            corrupt[40] = (corrupt[40].toInt() xor 1).toByte()
            Files.write(path, corrupt)
            assertFails { AdmissionStore(backend) }
            Files.write(path, bytes.copyOf(bytes.size - 1))
            assertFails { AdmissionStore(backend) }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `admission survives policy change without granting runtime authorization`() {
        val store = AdmissionStore(Memory())
        val artifact = record()
        store.recordSuccessfulInstall(AdmissionEvaluation(LightInstallDecision.Allow, artifact))
        assertIs<AdmissionOutcome.Admitted>(store.admitInstalled(artifact.packageName, artifact.apkSha256, fingerprint) {
            error("admission is not reconsidered for unchanged bytes")
        })
        assertEquals(artifact.admittedUnderLevel, store.admissionOf(artifact.packageName)?.admittedUnderLevel)
    }

    @Test fun `missing fingerprint never creates or matches admission`() {
        val backend = object : AdmissionPersistence {
            override fun read() = listOf(record())
            override fun write(records: List<AdmissionRecord>) = error("must not write")
        }
        val store = AdmissionStore(backend)
        assertFalse(store.hasAdmissionRecord("example.tool", "a".repeat(64)))
        assertEquals(AdmissionOutcome.EvaluationFailed, store.admitInstalled("example.tool", "a".repeat(64)) {
            error("missing installation evidence")
        })
        assertEquals(AdmissionOutcome.EvaluationFailed, store.recordSuccessfulInstall(
            AdmissionEvaluation(LightInstallDecision.Allow, record().copy(installationFingerprint = null))))
    }

    @Test fun `fallback cannot attach captured approval to a different installation fingerprint`() {
        val store = AdmissionStore(Memory())
        assertFailsWith<IllegalArgumentException> {
            store.admitInstalled("example.tool", "a".repeat(64), fingerprint.copy(lastUpdateTimeMillis = 2)) {
                AdmissionEvaluation(LightInstallDecision.Allow, record())
            }
        }
    }
}
