package com.thelightphone.sdk.server.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.LightSdkServer
import java.io.File
import java.io.FileInputStream

/**
 * Gates installation through [LightSdkServer.isPackageInstallable] before passing the APK to
 * [PackageInstaller]. The trust runtime is used when configured by the host; otherwise the gate
 * denies parsed APKs except at [ClientFilterLevel.AllowAllApks].
 */
class LightApkInstallWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "LightOSApkInstallWorker"
        const val KEY_APK_PATH = "apk_path"
    }

    override fun doWork(): Result {
        val apkPath = inputData.getString(KEY_APK_PATH) ?: return Result.failure()

        val apkFile = File(apkPath)
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "APK file does not exist or is empty: $apkPath")
            apkFile.delete()
            return Result.failure()
        }

        return try {
            val allowed =
                installFromPrivateCopy(
                    source = apkFile,
                    stagingDirectory = applicationContext.cacheDir,
                    isAllowed = { staged ->
                        val settings = LightSdkServer.provideSdkSettings(applicationContext)
                        LightSdkServer.isPackageInstallable(
                            settings.clientFilterLevel,
                            applicationContext,
                            staged,
                        )
                    },
                    install = { staged -> installApk(staged, apkPath) },
                )
            if (allowed) {
                Result.success()
            } else {
                Log.e(TAG, "Current client filter settings prevented install for: $apkPath")
                apkFile.delete()
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK: $apkPath", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                apkFile.delete()
                Result.failure()
            }
        }
    }

    private fun installApk(apkFile: File, originalApkPath: String) {
        val packageInstaller = applicationContext.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.use { s ->
            FileInputStream(apkFile).use { fis ->
                s.openWrite(apkFile.name, 0, apkFile.length()).use { out ->
                    fis.copyTo(out)
                    s.fsync(out)
                }
            }

            val intent =
                Intent(
                    applicationContext,
                    LightApkInstallResultReceiver::class.java,
                )
            intent.putExtra(KEY_APK_PATH, originalApkPath)
            val pi =
                PendingIntent.getBroadcast(
                    applicationContext,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_MUTABLE,
                )

            s.commit(pi.intentSender)
        }

        Log.d(TAG, "Install session committed for ${apkFile.name}")
    }
}
