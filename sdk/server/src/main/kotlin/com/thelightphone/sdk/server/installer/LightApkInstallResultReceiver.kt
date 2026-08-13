package com.thelightphone.sdk.server.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.thelightphone.sdk.server.LightSdkServer
import java.io.File

class LightApkInstallResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LightApkInstallResult"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val apkPath = intent.getStringExtra(LightApkInstallWorker.KEY_APK_PATH)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                LightSdkServer.onApkInstalled(apkPath)
                apkPath?.let { File(it).delete() }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }
            else -> {
                Log.e(TAG, "Failed to install APK: $apkPath, status=$status, message=$message")
                apkPath?.let { File(it).delete() }
            }
        }
    }
}
