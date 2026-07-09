package com.thelightphone.sdk.emulator

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.Log
import com.thelightphone.sdk.emulator.http.EmulatorHttpServer
import com.thelightphone.sdk.server.ClientCertType
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.shared.LightResult
import java.security.MessageDigest

// SHA-256 fingerprint of sdk/keys/lightsdk-dev.jks (alias: lightsdk-dev).
private const val LIGHTSDK_DEV_CERT_SHA256 =
    "B9C33E29B0CCAD2BFF11ACAB55F65A3C517EF4BC92CD9C77785366FA353D5F28"

class EmulatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(lockReceiver, filter)
        val mollysocketUriString = BuildConfig.MOLLYSOCKET_URI
        LightSdkServer.customServiceMethodResolver = { callingId, methodId, payload ->
            if (methodId == "GetMollySocketUri" && mollysocketUriString.isNotEmpty()) {
                val json = "{\"mollySocketUri\":\"$mollysocketUriString\"}"
                LightResult.Success(json)
            } else {
                LightResult.Error(LightResult.ErrorCode.Unknown)
            }
        }
        val pushDomain = BuildConfig.PUSH_DOMAIN.ifEmpty { "http://localhost:8090" }
        LightSdkServer.pushEndpointFetcher = { callingPackage, token, vapid ->
            Log.d("LightEmulator", "getting push endpoint for token: $token, vapid: $vapid")
            "$pushDomain/push/$token"
        }

        LightSdkServer.checkCert = { callingPackage ->
            checkLightSdkCert(callingPackage)
        }

        LightSdkServer.permissionActivity = LightSdkPermissionActivity::class.java

        EmulatorHttpServer(this).start()
    }

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                val i = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(SCREEN_OFF_FLAG, true)
                context.startActivity(i)   // fires while display is off
            }
        }
    }
}

// For the emulator, any apk built with LIGHTSDK_DEV_CERT_SHA256 is considered signed by Light
// Real LightOS will treat these as Unknown
private fun Context.checkLightSdkCert(callingPackage: String): ClientCertType {
    val info = try {
        packageManager.getPackageInfo(callingPackage, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (e: PackageManager.NameNotFoundException) {
        return ClientCertType.Unknown
    }
    val signingInfo = info.signingInfo ?: return ClientCertType.Unknown
    val signers: Array<Signature> = if (signingInfo.hasMultipleSigners()) {
        signingInfo.apkContentsSigners
    } else {
        signingInfo.signingCertificateHistory
    }
    val md = MessageDigest.getInstance("SHA-256")
    val matches = signers.any { sig ->
        md.digest(sig.toByteArray()).toHexString().equals(LIGHTSDK_DEV_CERT_SHA256, ignoreCase = true)
    }
    return if (matches) ClientCertType.LightSdkSignedUnverified else ClientCertType.Unknown
}