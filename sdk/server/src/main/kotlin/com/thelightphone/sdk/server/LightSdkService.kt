package com.thelightphone.sdk.server

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
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
    private val settings by lazy { LightSdkServer.provideSdkSettings(this) }

    private fun verifyCallerIsInstalledClient(callingId: Int): Boolean {
        val packages = packageManager.getPackagesForUid(callingId) ?: return false
        val clientFilterLevel = settings.clientFilterLevel
        if (clientFilterLevel == ClientFilterLevel.ExcludeAllApks) {
            Log.w(
                TAG,
                "User has disallowed external tools, yet an application is attempting to talk to SDK server"
            )
            return false
        }
        return packages.any { packageName ->
            val hasMarker = packageManager.queryBroadcastReceivers(
                Intent(LightConstants.ACTION_SDK_MARKER).setPackage(packageName),
                PackageManager.GET_META_DATA
            ).isNotEmpty()
            hasMarker && LightSdkServer.isPackageAllowed(clientFilterLevel, packageName)
        }
    }

    private fun issueToken(uid: Int): String {
        val token = java.util.UUID.randomUUID().toString()
        synchronized(tokensByUid) { tokensByUid[uid] = token }
        return token
    }

    private fun validateToken(uid: Int, token: String?): Boolean {
        return token != null && synchronized(tokensByUid) { tokensByUid[uid] } == token
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                LightConstants.TRANSACTION_REQUEST -> {
                    data.enforceInterface(LightConstants.ACTION_BIND_SDK_SERVICE)
                    val methodId = data.readString()
                    val payload = data.readString()
                    val token = data.readString()
                    val callingUid = getCallingUid()
                    val isGetToken = methodId == LightServiceMethod.GetToken.id
                    if (isGetToken) {
                        if (!verifyCallerIsInstalledClient(callingUid)) {
                            Log.w(TAG, "Rejected GetToken from unverified caller uid=$callingUid")
                            reply?.apply {
                                writeNoException()
                                writeInt(LightResult.ErrorCode.NoPermission.ordinal)
                                writeString("caller not verified")
                            }
                            return true
                        }
                    } else {
                        if (!validateToken(callingUid, token)) {
                            Log.w(TAG, "Rejected request with invalid token from uid=$callingUid")
                            reply?.apply {
                                writeNoException()
                                writeInt(LightResult.ErrorCode.NoPermission.ordinal)
                                writeString("invalid token")
                            }
                            return true
                        }
                    }

                    val result = if (methodId != null) {
                        runCatching { handleRequest(callingUid, methodId, payload) }
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

    private fun handleRequest(
        callingUid: Int,
        methodId: String,
        payload: String?
    ): LightResult<String> = runCatching {
        when (allMethods[methodId]) {
            LightServiceMethod.GetToken -> {
                val token = issueToken(callingUid)
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

            LightServiceMethod.GetKeyboardOptions -> {
                val optionsSnapshot = settings.keyboardOptions
                LightResult.Success(
                    LightServiceMethod.GetKeyboardOptions.encodeResponse(optionsSnapshot)
                )
            }

            LightServiceMethod.GetPermission -> {
                val request = LightServiceMethod.GetPermission.decodeRequest(payload!!)
                val permissionName = request.permissionName
                val permissionResult = runCatching {
                    if (!LightSdkServer.androidPermissionAllowed(callingUid, permissionName)) {
                        return@runCatching LightServiceMethod.GetPermission.Result.BlockedByServer
                    }
                    val androidResult =
                        checkPermission(permissionName, Binder.getCallingPid(), callingUid)
                    when (androidResult) {
                        PERMISSION_GRANTED -> LightServiceMethod.GetPermission.Result.Granted
                        PERMISSION_DENIED -> LightServiceMethod.GetPermission.Result.Denied
                        else -> LightServiceMethod.GetPermission.Result.Unknown
                    }
                }.getOrElse {
                    Log.e(TAG, "GetPermission error", it)
                    LightServiceMethod.GetPermission.Result.Unknown
                }

                LightResult.Success(
                    LightServiceMethod.GetPermission.encodeResponse(
                        LightServiceMethod.GetPermission.Response(
                            permissionResult = permissionResult
                        )
                    )
                )
            }

            LightServiceMethod.RequestPermissionComponent -> {
                val component = LightSdkServer.permissionActivity
                if (component == null) {
                    Log.e(
                        TAG,
                        "No permissions activity defined by server! Set LightSdkServer.permissionActivity"
                    )
                    LightResult.Error(
                        LightResult.ErrorCode.Unknown,
                        "Server has not defined a component to allow permission acceptance."
                    )
                } else {
                    startActivity(
                        Intent(this@LightSdkService, component).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    val componentName =
                        ComponentName(
                            this@LightSdkService,
                            component
                        ).flattenToString()
                    val response =
                        LightServiceMethod.RequestPermissionComponent.Response(componentName)
                    LightResult.Success(
                        LightServiceMethod.RequestPermissionComponent.encodeResponse(
                            response
                        )
                    )
                }
            }

            LightServiceMethod.GetUserPreferences -> {
                val preferencesSnapshot = settings.userPreferences
                LightResult.Success(
                    LightServiceMethod.GetUserPreferences.encodeResponse(preferencesSnapshot)
                )
            }

            null -> {
                // The app that wraps this server may be able to handle custom methods
                LightSdkServer.customServiceMethodResolver.invoke(callingUid, methodId, payload)
            }
        }
    }.getOrElse {
        Log.e(TAG, "Error handling client request:", it)
        LightResult.Error(LightResult.ErrorCode.Unknown)
    }


    override fun onBind(intent: Intent?): IBinder = binder
}
