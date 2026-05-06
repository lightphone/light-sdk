package com.thelightphone.sdk.server

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.thelightphone.sdk.shared.LightConstants
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.allMethods

class LightSdkService : Service() {

    companion object {
        private const val TAG = "LightSdkService"
    }

    // TODO something more robust
    private val tokensByUid = mutableMapOf<Int, String>()

    private fun verifyCallerIsInstalledClient(callingId: Int): Boolean {
        val packages = packageManager.getPackagesForUid(callingId) ?: return false
        return packages.any { packageName ->
            try {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val hasMarker = packageManager.queryBroadcastReceivers(
                    Intent(LightConstants.ACTION_SDK_MARKER).setPackage(packageName),
                    PackageManager.GET_META_DATA
                ).isNotEmpty()
                // TODO: verify signing certificate against known keys
                hasMarker
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private fun issueToken(uid: Int): String {
        val token = java.util.UUID.randomUUID().toString()
        synchronized(tokensByUid) { tokensByUid[uid] = token }
        return token
    }

    private fun validateToken(uid: Int, token: String?): Boolean {
        return synchronized(tokensByUid) { tokensByUid[uid] } == token
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                LightConstants.TRANSACTION_REQUEST -> {
                    data.enforceInterface(LightConstants.ACTION_BIND_SDK_SERVICE)
                    val methodId = data.readString()
                    val payload = data.readString()
                    val token = data.readString()
                    val callingId = getCallingUid()
                    val isGetToken = methodId == LightServiceMethod.GetToken.id
                    if (isGetToken) {
                        if (!verifyCallerIsInstalledClient(callingId)) {
                            Log.w(TAG, "Rejected GetToken from unverified caller uid=$callingId")
                            reply?.apply {
                                writeNoException()
                                writeInt(LightResult.ErrorCode.NoPermission.ordinal)
                                writeString("caller not verified")
                            }
                            return true
                        }
                    } else {
                        if (!validateToken(callingId, token)) {
                            Log.w(TAG, "Rejected request with invalid token from uid=$callingId")
                            reply?.apply {
                                writeNoException()
                                writeInt(LightResult.ErrorCode.NoPermission.ordinal)
                                writeString("invalid token")
                            }
                            return true
                        }
                    }

                    val result = if (methodId != null) {
                        runCatching { handleRequest(methodId, payload) }
                            .getOrElse {
                                Log.e(TAG, "Error handling request: $methodId", it)
                                LightResult.Error(LightResult.ErrorCode.Unknown)
                            }
                    } else LightResult.Error(
                        LightResult.ErrorCode.InvalidParameters,
                        "missing method id"
                    )
                    reply?.writeNoException()
                    when (result) {
                        is LightResult.Success -> {
                            reply?.writeInt(-1) // success sentinel
                            reply?.writeString(result.data)
                        }

                        is LightResult.Error -> {
                            reply?.writeInt(result.code.ordinal)
                            reply?.writeString(result.extra)
                        }
                    }
                    true
                }

                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    private fun handleRequest(methodId: String, payload: String?): LightResult<String> {
        return when (allMethods[methodId]) {
            LightServiceMethod.GetToken -> {
                val token = issueToken(Binder.getCallingUid())
                LightResult.Success(
                    LightServiceMethod.GetToken.encodeResponse(
                        LightServiceMethod.GetToken.Response(token = token)
                    )
                )
            }

            LightServiceMethod.GetVersion -> {
                LightResult.Success(
                    LightServiceMethod.GetVersion.encodeResponse(
                        LightServiceMethod.GetVersion.Response(version = BuildConfig.SDK_VERSION)
                    )
                )
            }

            LightServiceMethod.SetRingtone -> {
                val request = LightServiceMethod.SetRingtone.decodeRequest(payload!!)
                RingtoneManager.setActualDefaultRingtoneUri(
                    this,
                    request.type,
                    Uri.parse(request.uri)
                )
                LightResult.Success(LightServiceMethod.SetRingtone.encodeResponse(Unit))
            }

            null -> {
                Log.e(TAG, "Service method $methodId not found!")
                LightResult.Error(LightResult.ErrorCode.Unknown, "unknown method: $methodId")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
