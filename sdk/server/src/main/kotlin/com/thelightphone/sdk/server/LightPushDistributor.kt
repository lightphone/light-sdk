package com.thelightphone.sdk.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class LightPushDistributor : BroadcastReceiver() {

    companion object {
        private const val TAG = "LightPushDistributor"

        private const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
        private const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
        private const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"

        private const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
        private const val ACTION_REGISTRATION_FAILED =
            "org.unifiedpush.android.connector.REGISTRATION_FAILED"
        private const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
        private const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun isAllowed(context: Context, packageName: String): Boolean =
            LightSdkServer.isPackageAllowed(
                LightSdkServer.provideSdkSettings(context).clientFilterLevel,
                context,
                packageName,
            )

        fun sendMessage(context: Context, packageName: String, token: String, message: ByteArray) {
            if (!isAllowed(context, packageName)) return
            val intent =
                Intent(ACTION_MESSAGE).apply {
                    setPackage(packageName)
                    putExtra("token", token)
                    putExtra("bytesMessage", message)
                }
            context.sendBroadcast(intent)
        }

        fun sendLocalMessage(context: Context, packageName: String, message: ByteArray) {
            scope.launch {
                val registration =
                    LightPushRegistry.findLocal(context, packageName)
                        ?: run {
                            Log.w(TAG, "No local channel registration for given package")
                            return@launch
                        }
                sendMessage(context, packageName, registration.token, message)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return

        // getSentFromPackage() is only valid during onReceive, so call now
        val callerPackage = resolveCallerPackage(intent)

        when (intent.action) {
            ACTION_REGISTER -> runAsync { handleRegister(context, intent, callerPackage) }
            ACTION_UNREGISTER -> runAsync { handleUnregister(context, intent, callerPackage) }
            ACTION_MESSAGE_ACK -> {
                /* no-op for now */
            }
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun String.forLog(): String = take(7)

    private fun resolveCallerPackage(intent: Intent): String? {
        // API 34+: OS-verified sender package, set by the broadcasting framework.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sentFromPackage?.let {
                return it
            }
        }
        // Fallback for older OS versions: PendingIntent creator
        return intent
            .getParcelableExtra("pi", android.app.PendingIntent::class.java)
            ?.creatorPackage
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "Async receiver work failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleRegister(context: Context, intent: Intent, callerPackage: String?) {
        val token = intent.getStringExtra("token")

        if (token == null) {
            Log.e(TAG, "Registration missing token")
            sendRegistrationFailed(context, callerPackage, token)
            return
        }

        if (callerPackage == null) {
            Log.e(TAG, "Could not determine caller package")
            return
        }

        try {
            LightSdkServer.toolResources.withResource(callerPackage) {
                if (!isAllowed(context, callerPackage))
                    throw SecurityException("Push registration refused")
                registerWhileAllowed(context, intent, callerPackage, token)
            }
        } catch (_: SecurityException) {
            sendRegistrationFailed(context, callerPackage, token)
        }
    }

    private suspend fun registerWhileAllowed(
        context: Context,
        intent: Intent,
        callerPackage: String,
        token: String,
    ) {
        val channel = intent.getStringExtra("message")
        val vapid = intent.getStringExtra("vapid")

        val existing = LightPushRegistry.getByToken(context, token)
        if (existing != null && existing.packageName != callerPackage) {
            Log.w(
                TAG,
                "Token ${token.forLog()} already registered to another package; refusing re-registration from",
            )
            sendRegistrationFailed(context, callerPackage, token)
            return
        }

        // TODO move everything here into a worker?
        val endpoint =
            existing?.endpoint
                ?: try {
                    LightSdkServer.pushEndpointFetcher.invoke(callerPackage, token, vapid)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }

        currentCoroutineContext().ensureActive()
        if (endpoint == null) {
            Log.e(TAG, "Failed to fetch endpoint for token=${token.forLog()}")
            sendRegistrationFailed(context, callerPackage, token)
            return
        }

        LightPushRegistry.register(context, token, callerPackage, endpoint, channel, vapid)
        if (existing == null) {
            Log.i(TAG, "Registered with token ${token.forLog()} (channel=$channel)")
        } else {
            Log.i(TAG, "Re-registered with token ${token.forLog()} (channel=$channel)")
        }

        val response =
            Intent(ACTION_NEW_ENDPOINT).apply {
                setPackage(callerPackage)
                putExtra("token", token)
                putExtra("endpoint", endpoint)
            }
        context.sendBroadcast(response)
    }

    private fun sendRegistrationFailed(context: Context, packageName: String?, token: String?) {
        if (packageName == null) return
        val response =
            Intent(ACTION_REGISTRATION_FAILED).apply {
                setPackage(packageName)
                if (token != null) putExtra("token", token)
            }
        context.sendBroadcast(response)
    }

    private suspend fun handleUnregister(context: Context, intent: Intent, callerPackage: String?) {
        val token = intent.getStringExtra("token")
        if (token == null) {
            Log.e(TAG, "Unregistration missing token")
            return
        }

        if (callerPackage == null) {
            Log.w(TAG, "Could not determine caller package for unregister; dropping")
            return
        }

        val registration = LightPushRegistry.getByToken(context, token)
        if (registration == null) {
            Log.w(TAG, "Unregister for unknown token ${token.forLog()}; dropping")
            return
        }

        if (registration.packageName != callerPackage) {
            Log.w(
                TAG,
                "Unregister rejected: token ${token.forLog()} does not belong to caller; dropping",
            )
            return
        }

        LightPushRegistry.remove(context, token)
        Log.i(TAG, "Unregistered token ${token.forLog()}")

        val response =
            Intent(ACTION_UNREGISTERED).apply {
                setPackage(callerPackage)
                putExtra("token", token)
            }
        context.sendBroadcast(response)
    }
}
