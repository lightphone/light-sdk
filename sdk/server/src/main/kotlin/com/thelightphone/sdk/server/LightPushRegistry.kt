package com.thelightphone.sdk.server

import android.content.Context
import androidx.room.Room
import com.thelightphone.sdk.shared.LightConstants
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persistent store of UnifiedPush registrations, keyed by token.
 */
object LightPushRegistry {

    private val writes = PushRegistrationWrites(LightSdkServer.toolResources)

    @Volatile private var db: LightPushDb? = null

    private fun db(context: Context): LightPushDb = db ?: synchronized(this) {
        db ?: Room.databaseBuilder(
            context.applicationContext,
            LightPushDb::class.java,
            "light_push_registry.db",
        )
            .build()
            .also { db = it }
    }

    suspend fun register(
        context: Context,
        token: String,
        packageName: String,
        endpoint: String,
        channel: String? = null,
        vapid: String? = null,
    ) = writes.register(
        packageName,
        isAllowed = {
            LightSdkServer.isPackageAllowed(
                LightSdkServer.provideSdkSettings(context).clientFilterLevel, context, packageName,
            )
        },
    ) {
        db(context).pushRegistrations().upsert(
            token = token,
            packageName = packageName,
            endpoint = endpoint,
            channel = channel,
            vapid = vapid,
            now = System.currentTimeMillis(),
        )
    }

    suspend fun getByToken(context: Context, token: String): PushRegistration? =
        db(context).pushRegistrations().findByToken(token)

    suspend fun getByEndpoint(context: Context, endpoint: String): PushRegistration? =
        db(context).pushRegistrations().findByEndpoint(endpoint)

    /** Deletes the registration for [token] and returns the previous value, if any. */
    suspend fun remove(context: Context, token: String): PushRegistration? {
        val dao = db(context).pushRegistrations()
        val existing = dao.findByToken(token) ?: return null
        dao.deleteByToken(token)
        return existing
    }

    /** Deletes every registration for [packageName] (host resource cleanup on disable/purge). */
    suspend fun removePackage(context: Context, packageName: String) {
        writes.remove { db(context).pushRegistrations().deleteByPackage(packageName) }
    }

    /** Finds the local-channel registration for [packageName], if any. */
    suspend fun findLocal(context: Context, packageName: String): PushRegistration? =
        db(context).pushRegistrations()
            .findByPackageAndChannel(packageName, LightConstants.PUSH_CHANNEL_LOCAL)
}


internal class PushRegistrationWrites(private val resources: ToolResourceRegistry) {
    private val mutex = Mutex()

    suspend fun register(packageName: String, isAllowed: () -> Boolean, write: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        resources.withResource(packageName) {
            if (!isAllowed()) throw SecurityException("Push registration refused")
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                // Let the database write settle before package cleanup can acquire the lock.
                withContext(NonCancellable) { write() }
            }
        }
    }

    suspend fun remove(delete: suspend () -> Unit) = mutex.withLock { delete() }
}
