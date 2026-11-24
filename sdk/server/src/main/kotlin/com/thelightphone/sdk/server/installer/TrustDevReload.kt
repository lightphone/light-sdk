package com.thelightphone.sdk.server.installer

import android.content.Context
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.trust.LightTrustStore
import com.thelightphone.sdk.trust.SignedTrustBundle
import com.thelightphone.sdk.trust.TrustBundleCandidateStaging
import com.thelightphone.sdk.trust.TrustResult
import com.thelightphone.sdk.trust.TrustState
import java.io.File

/**
 * Dev-only candidate source, distinct from `POST /trust/bundle`: drop the exact
 * `bundle.json`/`bundle.sig` the signer produces at this directory (`adb push`, no
 * base64/JSON repackaging), then trigger [TrustDevReloadReceiver]. Loaded on startup
 * and on explicit reload, per subtask 09a. Restricted to shell/dev access by the
 * receiver's manifest permission, not by anything here.
 */
object TrustDevReload {
    const val ACTION_RELOAD = "com.thelightphone.sdk.server.trust.RELOAD_DEV_BUNDLE"

    sealed interface Outcome {
        data class Applied(val result: TrustResult<TrustState>) : Outcome
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
        return SignedTrustBundle(bytes.readBytes(), signature.readBytes())
    }

    /** Pure and testable without Android: callers resolve the Context-dependent pieces first. */
    fun reload(
        candidateDirectory: File,
        staging: TrustBundleCandidateStaging,
        store: LightTrustStore?,
    ): Outcome {
        if (store == null) return Outcome.StoreUnavailable
        val candidate = readCandidate(candidateDirectory) ?: return Outcome.NoCandidate
        staging.stage(candidate)
        return Outcome.Applied(staging.submit(store, candidate))
    }

    fun reload(context: Context): Outcome = reload(
        directoryFor(context),
        LightSdkServer.provideTrustBundleCandidateStaging(context),
        LightSdkServer.currentTrustStore,
    )
}
