package com.thelightphone.sdk.trust

import java.time.Instant

enum class AdmissionException { AllowAll, DeveloperHash, EmulatorDevelopmentKey }

/** Supplementary continuity evidence, not a unique platform installation ID. */
data class InstallationFingerprint(
    val baseApkPath: String,
    val firstInstallTimeMillis: Long,
    val lastUpdateTimeMillis: Long,
) {
    init {
        require(baseApkPath.startsWith("/") && baseApkPath.isNotBlank())
        require(firstInstallTimeMillis > 0 && lastUpdateTimeMillis > 0)
    }
}

data class AdmissionRecord(
    val packageName: String,
    val apkSha256: String,
    val signerSha256: String,
    val toolId: String?,
    val versionCode: Long,
    val admittedUnderBundleVersion: Long?,
    val admittedUnderLevel: LightTrustFilterLevel,
    val admittedAt: Instant,
    val exception: AdmissionException? = null,
    val installationFingerprint: InstallationFingerprint? = null,
) {
    init {
        require(packageName.isNotBlank())
        require(Regex("[0-9a-f]{64}").matches(apkSha256))
        require(Regex("[0-9a-f]{64}").matches(signerSha256))
        require(toolId == null || toolId.isNotBlank())
        require(toolId != null || exception != null)
        require(versionCode >= 0)
        require(admittedUnderBundleVersion == null || admittedUnderBundleVersion >= 0)
    }
}

/** One owner per backend. Failed reads must throw, never return an empty replacement. */
interface AdmissionPersistence {
    fun read(): List<AdmissionRecord>
    fun write(records: List<AdmissionRecord>)
}

/** Capture from current admission policy over the exact verified artifact. */
data class AdmissionEvaluation(val decision: LightInstallDecision, val record: AdmissionRecord?)

sealed interface AdmissionOutcome {
    data class Admitted(val record: AdmissionRecord) : AdmissionOutcome
    data class Refused(val decision: LightInstallDecision) : AdmissionOutcome
    data object PersistenceFailed : AdmissionOutcome
    data object EvaluationFailed : AdmissionOutcome
}

enum class AdmissionRemovalOutcome { Removed, PersistenceFailed }

class AdmissionStore(persistence: AdmissionPersistence) {
    private val persistence = persistence
    private var available = true
    private var records = persistence.read().also { values ->
        require(values.map { it.packageName }.distinct().size == values.size)
    }.associateBy { it.packageName }

    @Synchronized fun hasAdmissionRecord(
        packageName: String,
        apkSha256: String,
        installationFingerprint: InstallationFingerprint? = null,
    ): Boolean = available && installationFingerprint != null && records[packageName]?.let {
        it.apkSha256 == apkSha256 && it.installationFingerprint == installationFingerprint
    } == true

    @Synchronized fun admissionOf(packageName: String): AdmissionRecord? = if (available) records[packageName] else null

    @Synchronized fun removeAdmission(packageName: String): AdmissionRemovalOutcome {
        if (!available) return AdmissionRemovalOutcome.PersistenceFailed
        if (packageName !in records) return AdmissionRemovalOutcome.Removed
        val replacement = records - packageName
        try {
            persistence.write(replacement.values.toList())
        } catch (_: Exception) {
            available = false
            return AdmissionRemovalOutcome.PersistenceFailed
        }
        records = replacement
        return AdmissionRemovalOutcome.Removed
    }

    /** Call after successful installation, using the captured successful admission evaluation. */
    @Synchronized fun recordSuccessfulInstall(evaluation: AdmissionEvaluation): AdmissionOutcome {
        if (!available) return AdmissionOutcome.PersistenceFailed
        if (evaluation.decision != LightInstallDecision.Allow) return AdmissionOutcome.Refused(evaluation.decision)
        val record = requireNotNull(evaluation.record)
        if (record.installationFingerprint == null) return AdmissionOutcome.EvaluationFailed
        val replacement = records + (record.packageName to record)
        try {
            persistence.write(replacement.values.toList())
        } catch (_: Exception) {
            available = false
            return AdmissionOutcome.PersistenceFailed
        }
        records = replacement
        return AdmissionOutcome.Admitted(record)
    }

    /** Shared lazy migration/crash fallback. This establishes admission, not current runtime authorization. */
    @Synchronized fun admitInstalled(
        packageName: String,
        apkSha256: String,
        installationFingerprint: InstallationFingerprint? = null,
        evaluateCurrent: () -> AdmissionEvaluation,
    ): AdmissionOutcome {
        if (!available) return AdmissionOutcome.PersistenceFailed
        if (installationFingerprint == null) return AdmissionOutcome.EvaluationFailed
        records[packageName]?.takeIf {
            it.apkSha256 == apkSha256 && it.installationFingerprint == installationFingerprint
        }?.let {
            return AdmissionOutcome.Admitted(it)
        }
        val evaluation = try { evaluateCurrent() } catch (_: Exception) {
            return AdmissionOutcome.EvaluationFailed
        }
        if (evaluation.decision == LightInstallDecision.Allow) {
            require(evaluation.record?.packageName == packageName && evaluation.record.apkSha256 == apkSha256 &&
                evaluation.record.installationFingerprint == installationFingerprint)
        }
        return recordSuccessfulInstall(evaluation)
    }
}
