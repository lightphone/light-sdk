package com.thelightphone.sdk.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.thelightphone.sdk.trust.AdmissionRemovalOutcome

class ToolPackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in
                setOf(
                    Intent.ACTION_PACKAGE_REMOVED,
                    Intent.ACTION_PACKAGE_FULLY_REMOVED,
                    Intent.ACTION_PACKAGE_ADDED,
                    Intent.ACTION_PACKAGE_REPLACED,
                    Intent.ACTION_PACKAGE_CHANGED,
                )
        )
            return
        val packageName = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        if (packageName == context.packageName) return
        val uid = intent.getIntExtra(Intent.EXTRA_UID, -1)
        if (uid >= 0) {
            LightSdkServer.clientAuthorizations.revoke(uid)
            try {
                LightSdkServer.onRevokeToolUid(uid)
            } catch (error: Exception) {
                LightSdkServer.reportError("ToolEnforcement", error, "UID cleanup failed")
            }
        }
        LightSdkServer.toolTrustRuntime?.cache?.invalidate(packageName)
        if (
            intent.action != Intent.ACTION_PACKAGE_REMOVED &&
                intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED
        ) {
            LightSdkServer.notifyToolTrustChanged()
            return
        }
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        val outcome = LightSdkServer.toolTrustRuntime?.onPackageRemoved(packageName, replacing)
        LightSdkServer.toolEnforcement?.onPackageRemoved(packageName, replacing)
        if (!replacing && outcome != AdmissionRemovalOutcome.Removed) {
            Log.e("ToolTrust", "Could not durably remove admission for $packageName")
        }
    }
}

class ToolBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) LightSdkServer.notifyToolTrustChanged()
    }
}
