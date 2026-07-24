package com.thelightphone.sdk

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.thelightphone.sdk.shared.LightConstants
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.LightServiceMethod.RequestPermissionComponent.PERMISSION_NAME_KEY
import com.thelightphone.sdk.shared.getOrElse
import com.thelightphone.sdk.shared.getOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "LightServiceConnection"
private const val DEFAULT_TOKEN = "no_auth"

internal object LightServiceConnection : ServiceConnection {

    private var serviceBinder: IBinder? = null
    private var bound = false
    private var binderReady = CompletableDeferred<IBinder>()
    private var token: String = DEFAULT_TOKEN
    // Retained so we can rebind ourselves if the binding dies. applicationContext, so no leak.
    private var appContext: Context? = null
    private var serverPackage: String? = null

    fun bind(context: Context, serverPackage: String) {
        appContext = context.applicationContext
        this.serverPackage = serverPackage
        if (bound) return
        val intent = Intent(LightConstants.ACTION_BIND_SDK_SERVICE).apply {
            setPackage(serverPackage)
        }
        bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.w(TAG, "bindService returned false — is the server installed?")
        }
    }

    /** Forget the cached token so the next [ensureToken] re-authenticates with the server. */
    fun clearToken() {
        token = DEFAULT_TOKEN
    }

    private fun resetConnection() {
        serviceBinder = null
        clearToken()
        binderReady = CompletableDeferred()
    }

    fun unbind(context: Context) {
        if (!bound) return
        runCatching { context.unbindService(this) }
        bound = false
        serviceBinder = null
    }

    fun request(method: String, data: String): LightResult<String> {
        val binder = serviceBinder ?: return LightResult.Error(
            LightResult.ErrorCode.Unknown,
            "not connected"
        )
        val parcel = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            parcel.writeInterfaceToken(LightConstants.ACTION_BIND_SDK_SERVICE)
            parcel.writeString(method)
            parcel.writeString(data)
            parcel.writeString(token)
            binder.transact(LightConstants.TRANSACTION_REQUEST, parcel, reply, 0)
            reply.readException()
            val errorOrdinal = reply.readInt()
            if (errorOrdinal == -1) {
                LightResult.Success(reply.readString() ?: "")
            } else {
                val extra = reply.readString()
                val errorCode =
                    LightResult.ErrorCode.entries.getOrElse(errorOrdinal) { LightResult.ErrorCode.Unknown }
                LightResult.Error(errorCode, extra)
            }
        } catch (e: Exception) {
            Log.e(TAG, "request failed: method=$method", e)
            LightResult.Error(LightResult.ErrorCode.Unknown, e.message)
        } finally {
            parcel.recycle()
            reply.recycle()
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        Log.i(TAG, "Connected to LightSdkService")
        serviceBinder = binder
        // force re-auth if re-connected, token is likely invalid server-side now
        clearToken()
        if (binder != null) {
            binderReady.complete(binder)
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.w(TAG, "Disconnected from LightSdkService")
        resetConnection()
    }

    override fun onBindingDied(name: ComponentName?) {
        Log.w(TAG, "Binding to LightSdkService died, rebinding")
        resetConnection()
        val ctx = appContext ?: return
        val pkg = serverPackage ?: return
        runCatching { ctx.unbindService(this) }
            .onFailure { Log.w(TAG, "Failed to unbind service", it) }
        bound = false
        bind(ctx, pkg)
    }

    override fun onNullBinding(name: ComponentName?) {
        Log.e(TAG, "LightSdkService returned a null binding")
    }

    suspend fun awaitBinder(): IBinder = binderReady.await()

    fun ensureToken(): Boolean {
        if (token != DEFAULT_TOKEN) return true
        return when (val result = request(
            LightServiceMethod.GetToken.id,
            LightServiceMethod.GetToken.encodeRequest(Unit)
        )) {
            is LightResult.Success -> {
                token = LightServiceMethod.GetToken.decodeResponse(result.data).token
                Log.i(TAG, "Acquired service token")
                true
            }

            is LightResult.Error -> {
                Log.e(TAG, "Failed to acquire token: ${result.code} ${result.extra}")
                false
            }
        }
    }
}

suspend fun <TRequest, TResponse> callRemoteServiceMethod(
    method: LightServiceMethod<TRequest, TResponse>,
    body: TRequest,
    timeout: Duration = 5.seconds
): LightResult<TResponse> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val bound = withTimeoutOrNull(timeout) {
        LightServiceConnection.awaitBinder()
        LightServiceConnection.ensureToken()
        true
    }
    if (bound != true) {
        return@withContext LightResult.Error(LightResult.ErrorCode.Unknown, "Unable to bind to server")
    }
    val encoded = method.encodeRequest(body)
    var result = LightServiceConnection.request(method.id, encoded)
    if (result is LightResult.Error && result.code == LightResult.ErrorCode.InvalidToken) {
        // Case where the app is "allowed", but the token is no longer valid, allow one retry
        Log.i(TAG, "Token rejected calling remote method, re-auth + retry")
        LightServiceConnection.clearToken()
        LightServiceConnection.ensureToken()
        result = LightServiceConnection.request(method.id, encoded)
    }
    when (result) {
        is LightResult.Success -> LightResult.Success(method.decodeResponse(result.data))
        is LightResult.Error -> result
    }
}

suspend fun checkPermission(permission: String): LightResult<LightServiceMethod.GetPermission.Response> {
    return callRemoteServiceMethod(
        LightServiceMethod.GetPermission,
        LightServiceMethod.GetPermission.Request(permission)
    )
}

class PermissionRequestLauncher internal constructor(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val permission: String
) {
    fun launch() {
        scope.launch {
            val result = callRemoteServiceMethod(
                LightServiceMethod.RequestPermissionComponent,
                Unit
            ).getOrElse {
                Log.e(TAG, "Error fetching permission request component: $it")
                return@launch
            }
            val componentName = ComponentName.unflattenFromString(result.componentName)
            // we don't actually care about result, going to recheck through the server anyways
            activity.startActivityForResult(
                Intent().setComponent(componentName).putExtra(PERMISSION_NAME_KEY, permission),
                10101
            )
        }
    }
}

@Composable
fun rememberPermissionRequestLauncher(
    permission: String,
): PermissionRequestLauncher? {
    val context = LocalActivity.current
    val scope = rememberCoroutineScope()
    return remember(context, permission) {
        context?.let { PermissionRequestLauncher(it, scope, permission) }
    }
}
