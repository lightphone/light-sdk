package com.thelightphone.sdk.server

import com.thelightphone.sdk.trust.*
import java.io.File
import kotlin.time.Clock

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

    @Synchronized
    override fun put(key: StampCacheKey, artifact: ToolArtifact) {
        invalidate(key.packageName)
        entries[key] = artifact
    }

    @Synchronized
    override fun invalidate(packageName: String) {
        entries.keys.removeAll { it.packageName == packageName }
    }
}

/**
 * Host-side glue between artifact verification and the pure [LightToolEvaluator] policy.
 *
 * [evaluate] reads and verifies the on-disk APK, runs it through [LightToolEvaluator] and persists
 * a fresh [AdmissionRecord] via [admissionStore]. The admission record makes later calls stable
 * even if the trust bundle changes.
 *
 * @param verifier checks the APK's Light source stamp.
 * @param trustState current trust bundle + trusted stamp certs ()`null` means trust is unavailable)
 * @param admissionStore durable record of past successful admissions ()`null` means no persistence
 *   is configured)
 * @param developerSignerBypass returns true for a (package, signer cert) pair that should always
 *   install, e.g. a developer's own signing key.
 * @param emulatorDevelopmentSigner returns true for a signer cert recognized as an
 *   emulator/dev-build signer.
 */
class ToolTrustRuntime(
    private val verifier: StampVerifier,
    private val trustState: () -> TrustState?,
    private val admissionStore: AdmissionStore?,
    val cache: StampCache = InMemoryStampCache(),
    private val developerSignerBypass: (String, String) -> Boolean = { _, _ -> false },
    private val emulatorDevelopmentSigner: (String) -> Boolean = { false },
) : ToolTrustEvaluation {
    override fun invalidatePackage(packageName: String) = cache.invalidate(packageName)

    override fun onPackageRemoved(packageName: String, replacing: Boolean): AdmissionRemovalOutcome? {
        invalidatePackage(packageName)
        return if (replacing) null
        else
            admissionStore?.removeAdmission(packageName)
                ?: AdmissionRemovalOutcome.PersistenceFailed
    }

    /**
     * Evaluate if a package with the given metadata is allowed at a certain client filter level.
     *
     * Any unexpected failure (unreadable file, a throwing verifier, a persistence error) denies the
     * tool with [DenyReason.ArtifactUnavailable]. [ClientFilterLevel.AllowAllApks] and a matching
     * [developerSignerBypass] always resolve to Allow, since neither is supposed to depend on the
     * artifact being readable.
     */
    override fun evaluate(meta: LightSdkServer.ApkMetadata, level: ClientFilterLevel): ToolEvaluation =
        try {
            val signer =
                meta.signingCertSha256.singleOrNull() ?: error("Expected one current signer")
            val state =
                try {
                    trustState()
                } catch (_: Exception) {
                    null
                }
            val trustLevel = LightTrustFilterLevel.valueOf(level.name)
            val developerBypass = developerSignerBypass(meta.packageName, signer)
            val emulatorSigner = emulatorDevelopmentSigner(signer)

            val artifact =
                try {
                    val installationFingerprint =
                        if (meta.isInstalled)
                            InstallationFingerprint(
                                meta.apkPaths.single(),
                                meta.firstInstallTime,
                                meta.lastUpdateTime,
                            )
                        else null
                    readArtifact(meta, signer)
                        .copy(installationFingerprint = installationFingerprint)
                } catch (error: Exception) {
                    // if there's a bypass, allow regardless of the exception.
                    // this is not applied before because we want the proper evaluation result
                    // regardless of the bypass
                    if (level == ClientFilterLevel.AllowAllApks || developerBypass) {
                        val bypassException =
                            if (level == ClientFilterLevel.AllowAllApks) AdmissionException.AllowAll
                            else AdmissionException.DeveloperHash
                        return exceptionWithoutArtifact(bypassException, state?.version)
                    }
                    throw error
                }

            // convenience method that populate the LightToolEvaluator inputs,
            // except the admission record, which might change between other `evaluate` calls.
            fun evaluate(admission: AdmissionRecord?) =
                LightToolEvaluator.evaluate(
                    artifact = artifact,
                    state = state,
                    level = trustLevel,
                    admission = admission,
                    developerSignerBypass = developerBypass,
                    emulatorDevelopmentSigner = emulatorSigner,
                )

            // use an existing admission if it exists
            val existingAdmission =
                if (meta.isInstalled) admissionStore?.admissionOf(meta.packageName) else null
            val initial = evaluate(existingAdmission)

            // don't write admission if the package was accepted through a bypass
            val skipsAdmissionWrite =
                !meta.isInstalled ||
                    initial.outcome !is ToolEvaluationOutcome.PolicyDecision ||
                    initial.decision != LightInstallDecision.Allow
            if (skipsAdmissionWrite) {
                initial
            } else {
                // this block only runs when no admission record covers this exact apk + install
                val outcome =
                    admissionStore?.admitInstalled(
                        meta.packageName,
                        artifact.apkSha256,
                        artifact.installationFingerprint,
                    ) {

                        // check against a null admission (as if there's no past record), because a
                        // new
                        // admission record should stand on its own
                        val fresh = evaluate(null)
                        val record =
                            if (
                                fresh.outcome is ToolEvaluationOutcome.PolicyDecision &&
                                    fresh.decision == LightInstallDecision.Allow
                            )
                                AdmissionRecord(
                                    meta.packageName,
                                    artifact.apkSha256,
                                    signer,
                                    (artifact.statement as? StatementReadResult.Present)
                                        ?.statement
                                        ?.tool
                                        ?.id,
                                    meta.versionCode,
                                    state?.version,
                                    trustLevel,
                                    Clock.System.now(),
                                    installationFingerprint = artifact.installationFingerprint,
                                )
                            else null
                        AdmissionEvaluation(fresh.decision, record)
                    }
                when (outcome) {
                    // to be extra safe with regards to race conditions, re-evaluate with null
                    // admission
                    // to guarantee that the artifacts evaluation stands by itself
                    is AdmissionOutcome.Admitted -> evaluate(outcome.record)
                    // the fresh re-check inside admitInstalled failed policy.
                    is AdmissionOutcome.Refused ->
                        initial.copy(
                            outcome = ToolEvaluationOutcome.PolicyDecision(outcome.decision)
                        )
                    // don't return allow decisions that weren't persisted
                    else ->
                        initial.copy(
                            outcome =
                                ToolEvaluationOutcome.PolicyDecision(
                                    LightInstallDecision.Deny(DenyReason.AdmissionUnavailable)
                                )
                        )
                }
            }
        } catch (_: Exception) {
            // Anything unexpected (bad metadata, a crashing verifier, etc.) fails closed, except
            // that
            // AllowAllApks must still mean "don't check anything" even when evaluation itself blew
            // up.
            if (level == ClientFilterLevel.AllowAllApks)
                exceptionWithoutArtifact(AdmissionException.AllowAll)
            else unavailable(DenyReason.ArtifactUnavailable)
        }

    /**
     * Reads, hashes, and verifies the APK named by [meta], reusing the cached [ToolArtifact] is
     * possible. Installed artifacts are cached and archives are always read fresh, because a
     * pre-install APK file could have been replaced on disk and there's no signal to invalidate its
     * cache entry.
     */
    private fun readArtifact(meta: LightSdkServer.ApkMetadata, signer: String): ToolArtifact {
        // Split artifact binding is deliberately unsupported until a whole-install hash is
        // specified.
        require(meta.apkPaths.size == 1) { "Split APK admission is unsupported" }
        val files = meta.apkPaths.map(::File)
        require(files.all { it.isFile })
        fun key() =
            StampCacheKey(
                meta.packageName,
                meta.lastUpdateTime,
                meta.apkPaths.toList(),
                meta.versionCode,
                signer,
                files.map { it.length() },
                files.map { it.lastModified() },
            )
        val before = key()
        if (meta.isInstalled)
            cache.get(before)?.let {
                // the cache key alone doesn't establish identity, so we confirm the hit actually
                // matches
                // before trusting a verification result computed for a different
                // package/signer/version.
                require(
                    it.packageName == meta.packageName &&
                        it.signerSha256 == signer &&
                        it.versionCode == meta.versionCode
                )
                return it
            }
        val artifact =
            ToolArtifact(
                meta.packageName,
                apkSha256(files.single()),
                signer,
                meta.versionCode,
                verifier.verify(meta.apkPaths),
                LightTrustStatementReader.read(files.single()),
            )
        // if the file changed underneath verification, the key computed now differs from `before`,
        // so this result must not be cached (or returned) as if it were stable
        require(before == key()) { "Artifact changed during verification" }
        if (meta.isInstalled && artifact.stamp !is StampResult.Unavailable)
            cache.put(before, artifact)
        return artifact
    }

    companion object {
        fun unavailable(reason: DenyReason = DenyReason.TrustUnavailable) =
            ToolEvaluation(
                provenance = ToolProvenance.Unverified,
                approvedByBundle = false,
                bundleVersion = null,
                outcome = ToolEvaluationOutcome.PolicyDecision(LightInstallDecision.Deny(reason)),
            )

        internal fun exceptionWithoutArtifact(
            exception: AdmissionException,
            bundleVersion: Long? = null,
        ) =
            ToolEvaluation(
                provenance = ToolProvenance.Unverified,
                approvedByBundle = false,
                bundleVersion = bundleVersion,
                outcome = ToolEvaluationOutcome.ExceptionGranted(exception),
            )
    }
}
