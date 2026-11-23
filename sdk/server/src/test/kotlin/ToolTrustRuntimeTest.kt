import com.thelightphone.sdk.server.*
import com.thelightphone.sdk.trust.*
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ToolTrustRuntimeTest {
    @Test fun `missed uninstall with identical bytes and timestamps at a new path needs fresh admission`() {
        val directory = Files.createTempDirectory("runtime-fingerprint-")
        val original = directory.resolve("original.apk").toFile()
        val reinstalled = directory.resolve("reinstalled.apk").toFile()
        try {
            ZipOutputStream(original.outputStream()).use {
                it.putNextEntry(ZipEntry("META-INF/light-trust.json")); it.write(json.toByteArray()); it.closeEntry()
            }
            Files.copy(original.toPath(), reinstalled.toPath())
            val backend = Memory()
            var state = TrustState(null, setOf(stamp))
            fun runtime() = ToolTrustRuntime(StampVerifier { StampResult.Verified(stamp, emptyList()) }, { state }, AdmissionStore(backend))
            val meta = LightSdkServer.ApkMetadata("example.tool", "1", 7, listOf(signer), listOf(original.path),
                20, firstInstallTime = 10)
            assertEquals(LightInstallDecision.Allow, runtime().evaluate(meta, ClientFilterLevel.AllowLightSignedApks).decision)
            assertEquals(InstallationFingerprint(original.path, 10, 20), backend.values.single().installationFingerprint)
            state = TrustState(null, emptySet())
            val restarted = runtime()
            assertEquals(LightInstallDecision.Allow, restarted.evaluate(meta, ClientFilterLevel.AllowLightSignedApks).decision)
            for (current in listOf(meta.copy(apkPaths = listOf(reinstalled.path)),
                meta.copy(firstInstallTime = 11), meta.copy(lastUpdateTime = 21))) {
                assertEquals(LightInstallDecision.Deny(DenyReason.UntrustedStampCert),
                    restarted.evaluate(current, ClientFilterLevel.AllowLightSignedApks).decision)
            }
            assertEquals(LightInstallDecision.Deny(DenyReason.ArtifactUnavailable),
                restarted.evaluate(meta.copy(firstInstallTime = 0), ClientFilterLevel.AllowLightSignedApks).decision)
            assertEquals(LightInstallDecision.Allow,
                restarted.evaluate(meta.copy(firstInstallTime = 0), ClientFilterLevel.AllowAllApks).decision)
            assertEquals(apkSha256(original), apkSha256(reinstalled))
        } finally {
            original.delete(); reinstalled.delete(); Files.deleteIfExists(directory)
        }
    }

    @Test fun `explicit bypass survives artifact read failure without granting approval`() {
        val meta = LightSdkServer.ApkMetadata("example.tool", "1", 7,
            listOf("a".repeat(64)), listOf("/does-not-exist"), 1)
        val runtime = ToolTrustRuntime(StampVerifier { error("must not verify missing artifact") },
            { null }, null, developerSignerBypass = { _, _ -> true })
        for (level in ClientFilterLevel.entries) {
            val result = runtime.evaluate(meta, level)
            assertEquals(LightInstallDecision.Allow, result.decision)
            val exception = if (level == ClientFilterLevel.AllowAllApks) {
                AdmissionException.AllowAll
            } else {
                AdmissionException.DeveloperHash
            }
            assertEquals(ToolEvaluationOutcome.ExceptionGranted(exception), result.outcome)
            assertFalse(result.mayUseApprovalProtectedApi)
        }
    }
    private val signer = "a".repeat(64)
    private val stamp = "b".repeat(64)
    private val json = """{"schemaVersion":1,"tool":{"id":"example.tool","versionCode":7,"versionName":"1","gitUrl":"https://example.test","gitCommit":"abc"},"sdkGitRef":"sdk","devId":"dev","signerSha256":"$signer","buildId":"build","issuedAt":"2026-11-17T00:00:00Z"}"""
    private class Memory : AdmissionPersistence {
        var values = emptyList<AdmissionRecord>()
        var fail = false
        override fun read() = values
        override fun write(records: List<AdmissionRecord>) {
            if (fail) error("disk unavailable")
            values = records
        }
    }

    @Test fun `cache avoids repeated verification but current trust and admission remain separate`() {
        val file = Files.createTempFile("runtime-", ".apk").toFile()
        try {
            ZipOutputStream(file.outputStream()).use {
                it.putNextEntry(ZipEntry("META-INF/light-trust.json"))
                it.write(json.toByteArray())
                it.closeEntry()
            }
            val backend = Memory()
            var state = TrustState(null, setOf(stamp))
            var verifications = 0
            val runtime = ToolTrustRuntime(StampVerifier { verifications++; StampResult.Verified(stamp, emptyList()) },
                { state }, AdmissionStore(backend))
            val meta = LightSdkServer.ApkMetadata("example.tool", "1", 7, listOf(signer), listOf(file.path), 1, firstInstallTime = 1)
            repeat(2) { assertEquals(LightInstallDecision.Allow, runtime.evaluate(meta, ClientFilterLevel.AllowLightSignedApks).decision) }
            assertEquals(1, verifications)
            assertEquals(1, backend.values.size)
            state = TrustState(null, emptySet())
            assertEquals(LightInstallDecision.Allow, runtime.evaluate(meta, ClientFilterLevel.AllowLightSignedApks).decision)
            assertNull(runtime.onPackageRemoved(meta.packageName, replacing = true))
            assertEquals(LightInstallDecision.Allow, runtime.evaluate(meta, ClientFilterLevel.AllowLightSignedApks).decision)
            assertEquals(LightInstallDecision.Deny(DenyReason.NotApproved),
                runtime.evaluate(meta, ClientFilterLevel.AllowLightApprovedApks).decision)
            assertEquals(LightInstallDecision.Deny(DenyReason.UntrustedStampCert),
                runtime.evaluate(meta.copy(isInstalled = false), ClientFilterLevel.AllowLightSignedApks).decision)
            runtime.cache.invalidate(meta.packageName)
            runtime.evaluate(meta, ClientFilterLevel.AllowLightSignedApks)
            assertEquals(4, verifications)
            assertEquals(AdmissionRemovalOutcome.Removed, runtime.onPackageRemoved(meta.packageName, replacing = false))
            assertEquals(LightInstallDecision.Deny(DenyReason.UntrustedStampCert),
                runtime.evaluate(meta, ClientFilterLevel.AllowLightSignedApks).decision)
        } finally { file.delete() }
    }

    @Test fun `admission failure refuses ordinary execution without granting protected access`() {
        val file = Files.createTempFile("runtime-", ".apk").toFile()
        try {
            ZipOutputStream(file.outputStream()).use {
                it.putNextEntry(ZipEntry("META-INF/light-trust.json")); it.write(json.toByteArray()); it.closeEntry()
            }
            val backend = Memory().apply { fail = true }
            val runtime = ToolTrustRuntime(StampVerifier { StampResult.Verified(stamp, emptyList()) },
                { TrustState(null, setOf(stamp)) }, AdmissionStore(backend))
            val meta = LightSdkServer.ApkMetadata("example.tool", "1", 7, listOf(signer), listOf(file.path), 1, firstInstallTime = 1)
            assertEquals(LightInstallDecision.Deny(DenyReason.AdmissionUnavailable),
                runtime.evaluate(meta, ClientFilterLevel.AllowLightSignedApks).decision)
            assertTrue(backend.values.isEmpty())
            assertEquals(LightInstallDecision.Allow,
                runtime.evaluate(meta, ClientFilterLevel.AllowAllApks).decision)
        } finally { file.delete() }
    }

    @Test fun `archive evaluation never writes admission`() {
        val file = Files.createTempFile("runtime-", ".apk").toFile()
        try {
            ZipOutputStream(file.outputStream()).use {
                it.putNextEntry(ZipEntry("META-INF/light-trust.json")); it.write(json.toByteArray()); it.closeEntry()
            }
            val backend = Memory()
            val runtime = ToolTrustRuntime(StampVerifier { StampResult.Verified(stamp, emptyList()) },
                { TrustState(null, setOf(stamp)) }, AdmissionStore(backend))
            val meta = LightSdkServer.ApkMetadata("example.tool", "1", 7, listOf(signer), listOf(file.path), 1, false)
            assertEquals(LightInstallDecision.Allow, runtime.evaluate(meta, ClientFilterLevel.AllowLightSignedApks).decision)
            assertTrue(backend.values.isEmpty())
        } finally { file.delete() }
    }
}
