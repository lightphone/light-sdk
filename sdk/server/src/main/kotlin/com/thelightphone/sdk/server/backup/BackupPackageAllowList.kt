package com.thelightphone.sdk.server.backup

import android.content.SharedPreferences

typealias PackageToLabelMap = Map<String, String>
data class AllowListItem(val label: String, val packageName: String, val backupAllowed: Boolean)
class BackupPackageAllowList(
    private val internalPackages: PackageToLabelMap,
    private val preferences: SharedPreferences,
    private val listExternalPackages: suspend () -> PackageToLabelMap
) {
    companion object {
        private const val ALLOWED_KEY = "LIGHT_BACKUP_ALLOWED_PKGS"
        private const val DISALLOWED_KEY = "LIGHT_BACKUP_DISALLOWED_PKGS"
    }
    suspend fun getAllowList(): List<AllowListItem> {
        val userAllowed = preferences.getStringSet(ALLOWED_KEY, emptySet<String>())
        val userDisallowed = preferences.getStringSet(DISALLOWED_KEY, emptySet<String>())

    }

    fun setPackageAllowed(packageName: String, backupAllowed: Boolean) {
        var userAllowed = preferences.getStringSet(ALLOWED_KEY, emptySet<String>()).orEmpty()
        var userDisallowed = preferences.getStringSet(DISALLOWED_KEY, emptySet<String>()).orEmpty()
        if (backupAllowed) {
            userAllowed += packageName
            userDisallowed -= packageName
        } else {
            userDisallowed += packageName
            userAllowed -= packageName
        }
    }
}