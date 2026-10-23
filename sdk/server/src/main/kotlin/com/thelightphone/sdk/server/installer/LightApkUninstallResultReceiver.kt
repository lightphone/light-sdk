package com.thelightphone.sdk.server.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.thelightphone.sdk.server.LightSdkServer

class LightApkUninstallResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LightApkUninstallResult"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(LightApkUninstallWorker.KEY_PACKAGE_NAME)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                packageName?.let { LightSdkServer.onApkUninstalled(it) }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }
            else -> {
                Log.e(TAG, "Failed to uninstall package: $packageName, status=$status, message=$message")
            }
        }
    }
}
