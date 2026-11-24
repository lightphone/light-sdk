package com.thelightphone.sdk.server.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Exported but gated by the `android.permission.DUMP` manifest requirement, which the
 * `shell`/`root` callers `adb shell am broadcast` runs as already hold and ordinary
 * third-party apps do not. Dev-only; see [TrustDevReload].
 */
class TrustDevReloadReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "TrustDevReloadReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (val outcome = TrustDevReload.reload(context)) {
            is TrustDevReload.Outcome.Applied -> Log.i(TAG, "Dev bundle reload: ${outcome.result}")
            is TrustDevReload.Outcome.ReadFailed ->
                Log.e(TAG, "Unable to read dev bundle candidate", outcome.cause)
            TrustDevReload.Outcome.NoCandidate ->
                Log.i(TAG, "Dev bundle reload: no candidate at ${TrustDevReload.directoryFor(context)}")
            TrustDevReload.Outcome.StoreUnavailable ->
                Log.e(TAG, "Dev bundle reload: trust store unavailable")
        }
    }
}
