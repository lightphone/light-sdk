package com.thelightphone.sdk.server.backup

import android.content.SharedPreferences
import com.thelightphone.sdk.server.LightSdkServer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BackupPackageAllowList(
    private val preferences: SharedPreferences
) {
    companion object {
        private const val ALLOWED_KEY = "LIGHT_BACKUP_ALLOWED_PKGS"
        private const val DISALLOWED_KEY = "LIGHT_BACKUP_DISALLOWED_PKGS"
    }

    private val mutex = Mutex()
    @Volatile private var cachedAllowList: Set<String>? = null
    @Volatile private var cachedDisallowList: Set<String>? = null

    fun userAllowedTool(tool: BackupCapableTool): Boolean =
        if (!tool.internalTool) {
            allowList().contains(tool.packageName)
        } else {
            !disallowList().contains(tool.packageName)
        }

    suspend fun setPackageAllowed(packageName: String, backupAllowed: Boolean) = mutex.withLock {
        var userAllowed = allowList()
        var userDisallowed = disallowList()
        if (backupAllowed) {
            userAllowed += packageName
            userDisallowed -= packageName
        } else {
            userDisallowed += packageName
            userAllowed -= packageName
        }
        // sdk/server has no androidx.core dependency, so the KTX `edit { }` extension the IDE
        // suggests here isn't actually available in this module.
        preferences.edit()
            .putStringSet(ALLOWED_KEY, userAllowed)
            .putStringSet(DISALLOWED_KEY, userDisallowed)
            .apply()
        cachedAllowList = userAllowed
        cachedDisallowList = userDisallowed
    }

    // Cache fields are @Volatile and this cache-miss population is idempotent,
    // so concurrent calls from outside `mutex` (i.e. from userAllowedPackage)
    // are safe without locking.
    private fun allowList(): Set<String> =
        cachedAllowList ?: preferences.getStringSet(ALLOWED_KEY, emptySet<String>()).orEmpty()
            .also { cachedAllowList = it }

    private fun disallowList(): Set<String> =
        cachedDisallowList ?: preferences.getStringSet(DISALLOWED_KEY, emptySet<String>()).orEmpty()
            .also { cachedDisallowList = it }
}