package com.thelightphone.sdk.server

import com.thelightphone.sdk.trust.*
import java.io.File
import java.time.Instant

data class StampCacheKey(
    val packageName: String,
    val lastUpdateTime: Long,
    val paths: List<String>,
    val versionCode: Long,
    val signerSha256: String,
    val fileSizes: List<Long>,
    val fileModifiedTimes: List<Long>,
)

interface StampCache {
    fun get(key: StampCacheKey): ToolArtifact?
    fun put(key: StampCacheKey, artifact: ToolArtifact)
    fun invalidate(packageName: String)
}

class InMemoryStampCache : StampCache {
    private val entries = mutableMapOf<StampCacheKey, ToolArtifact>()
    @Synchronized override fun get(key: StampCacheKey) = entries[key]
    @Synchronized override fun put(key: StampCacheKey, artifact: ToolArtifact) {
        invalidate(key.packageName)
        entries[key] = artifact
    }
    @Synchronized override fun invalidate(packageName: String) {
        entries.keys.removeAll { it.packageName == packageName }
    }
}

class ToolTrustRuntime(
    private val verifier: StampVerifier,
    private val trustState: () -> TrustState?,
    private val admissionStore: AdmissionStore?,
    val cache: StampCache = InMemoryStampCache(),
    private val developerSignerBypass: (String, String) -> Boolean = { _, _ -> false },
    private val emulatorDevelopmentSigner: (String) -> Boolean = { false },
) {
    fun onPackageRemoved(packageName: String, replacing: Boolean): AdmissionRemovalOutcome? {
        cache.invalidate(packageName)
        return if (replacing) null else admissionStore?.removeAdmission(packageName)
            ?: AdmissionRemovalOutcome.PersistenceFailed
    }

    fun evaluate(meta: LightSdkServer.ApkMetadata, level: ClientFilterLevel): ToolEvaluation = try {
        val signer = meta.signingCertSha256.singleOrNull() ?: error("Expected one current signer")
        val state = try { trustState() } catch (_: Exception) { null }
        val trustLevel = LightTrustFilterLevel.valueOf(level.name)
        val developerBypass = developerSignerBypass(meta.packageName, signer)
        val emulatorSigner = emulatorDevelopmentSigner(signer)
        val artifact = try {
            val fingerprint = if (meta.isInstalled) InstallationFingerprint(
                meta.apkPaths.single(), meta.firstInstallTime, meta.lastUpdateTime) else null
            readArtifact(meta, signer).copy(installationFingerprint = fingerprint)
        } catch (error: Exception) {
            if (level == ClientFilterLevel.AllowAllApks || developerBypass) {
                return unavailable(DenyReason.ArtifactUnavailable).copy(
                    decision = LightInstallDecision.Allow,
                    exception = if (level == ClientFilterLevel.AllowAllApks) AdmissionException.AllowAll
                        else AdmissionException.DeveloperHash,
                    bundleVersion = state?.version,
                )
            }
            throw error
        }
        fun evaluate(admission: AdmissionRecord?) = LightToolEvaluator.evaluate(
            artifact, state, trustLevel, admission, developerBypass, emulatorSigner)
        val initial = evaluate(if (meta.isInstalled) admissionStore?.admissionOf(meta.packageName) else null)
        if (!meta.isInstalled || initial.decision != LightInstallDecision.Allow || initial.exception != null) {
            initial
        } else {
            val outcome = admissionStore?.admitInstalled(meta.packageName, artifact.apkSha256, artifact.installationFingerprint) {
                val fresh = evaluate(null)
                AdmissionEvaluation(fresh.decision, if (fresh.decision == LightInstallDecision.Allow)
                    AdmissionRecord(meta.packageName, artifact.apkSha256, signer,
                        (artifact.statement as? StatementReadResult.Present)?.statement?.tool?.id,
                        meta.versionCode, state?.version, trustLevel, Instant.now(), fresh.exception,
                        artifact.installationFingerprint) else null)
            }
            when (outcome) {
                is AdmissionOutcome.Admitted -> evaluate(outcome.record)
                is AdmissionOutcome.Refused -> initial.copy(decision = outcome.decision)
                else -> initial.copy(decision = LightInstallDecision.Deny(DenyReason.AdmissionUnavailable))
            }
        }
    } catch (_: Exception) {
        unavailable(DenyReason.ArtifactUnavailable).let {
            if (level == ClientFilterLevel.AllowAllApks) it.copy(
                decision = LightInstallDecision.Allow, exception = AdmissionException.AllowAll) else it
        }
    }

    private fun readArtifact(meta: LightSdkServer.ApkMetadata, signer: String): ToolArtifact {
        // Split artifact binding is deliberately unsupported until a whole-install hash is specified.
        require(meta.apkPaths.size == 1) { "Split APK admission is unsupported" }
        val files = meta.apkPaths.map(::File)
        require(files.all { it.isFile })
        fun key() = StampCacheKey(meta.packageName, meta.lastUpdateTime, meta.apkPaths.toList(),
            meta.versionCode, signer, files.map { it.length() }, files.map { it.lastModified() })
        val before = key()
        if (meta.isInstalled) cache.get(before)?.let {
            require(it.packageName == meta.packageName && it.signerSha256 == signer && it.versionCode == meta.versionCode)
            return it
        }
        val artifact = ToolArtifact(meta.packageName, apkSha256(files.single()), signer,
            meta.versionCode, verifier.verify(meta.apkPaths), LightTrustStatementReader.read(files.single()))
        require(before == key()) { "Artifact changed during verification" }
        if (meta.isInstalled && artifact.stamp !is StampResult.Unavailable) cache.put(before, artifact)
        return artifact
    }

    companion object {
        fun unavailable(reason: DenyReason = DenyReason.TrustUnavailable) = ToolEvaluation(
            ToolProvenance.Unverified, false, null, LightInstallDecision.Deny(reason), null, null)
    }
}
