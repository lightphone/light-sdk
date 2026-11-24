package com.thelightphone.sdk.server.installer

import android.content.Context
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.trust.FileTrustPersistence
import com.thelightphone.sdk.trust.LightTrustStore
import com.thelightphone.sdk.trust.SignedTrustBundle
import com.thelightphone.sdk.trust.TrustBundleCandidateStaging
import com.thelightphone.sdk.trust.TrustResult
import com.thelightphone.sdk.trust.TrustState
import java.io.File
import java.io.IOException

/**
 * Dev-only candidate source, distinct from `POST /trust/bundle`: drop the exact
 * `bundle.json`/`bundle.sig` the signer produces at this directory (`adb push`, no
 * base64/JSON repackaging), then trigger [TrustDevReloadReceiver]. Loaded on startup
 * and on explicit reload, per subtask 09a. Restricted to shell/dev access by the
 * receiver's manifest permission, not by anything here.
 */
object TrustDevReload {
    sealed interface Outcome {
        data class Applied(val result: TrustResult<TrustState>) : Outcome
        data class ReadFailed(val cause: IOException) : Outcome
        data object NoCandidate : Outcome
        data object StoreUnavailable : Outcome
    }

    var directoryFor: (Context) -> File = {
        File(it.getExternalFilesDir(null), "trust-dev-reload")
    }

    /** Pure and testable without Android: both files must exist, or there is no candidate. */
    fun readCandidate(directory: File): SignedTrustBundle? {
        val bytes = File(directory, "bundle.json")
        val signature = File(directory, "bundle.sig")
        if (!bytes.isFile || !signature.isFile) return null
        return SignedTrustBundle(
            bytes.readBounded(FileTrustPersistence.MAX_BUNDLE_BYTES),
            signature.readBounded(FileTrustPersistence.MAX_SIGNATURE_BYTES),
        )
    }

    /** Pure and testable without Android: callers resolve the Context-dependent pieces first. */
    fun reload(
        candidateDirectory: File,
        staging: TrustBundleCandidateStaging,
        store: LightTrustStore?,
    ): Outcome {
        if (store == null) return Outcome.StoreUnavailable
        val candidate = try {
            readCandidate(candidateDirectory)
        } catch (error: IOException) {
            return Outcome.ReadFailed(error)
        } ?: return Outcome.NoCandidate
        return Outcome.Applied(staging.stageAndSubmit(store, candidate))
    }

    private fun File.readBounded(limit: Int): ByteArray = inputStream().use { stream ->
        val bytes = stream.readNBytes(limit + 1)
        if (bytes.size > limit) throw IOException("$name exceeds $limit bytes")
        bytes
    }

    fun reload(context: Context): Outcome = reload(
        directoryFor(context),
        LightSdkServer.provideTrustBundleCandidateStaging(context),
        LightSdkServer.currentTrustStore,
    )
}
