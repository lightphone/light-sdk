package com.thelightphone.sdk.server.backup

import android.content.SharedPreferences
import com.thelightphone.backup.RemoteConnection
import com.thelightphone.backup.WebDavCredentials
import com.thelightphone.toolmanager.KeyCipher

data class StoredWebDavCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
    // Plain http sends credentials in the clear, only true if the user explicitly opted in.
    val allowInsecureHttp: Boolean,
) {
    override fun toString() =
        "StoredWebDavCredentials(serverUrl=$serverUrl, username=$username, password=***, allowInsecureHttp=$allowInsecureHttp)"

    fun toConnection() = RemoteConnection.WebDav(
        serverUrl,
        WebDavCredentials.Basic(username, password),
        allowInsecureHttp,
    )
}

interface WebDavCredentialStorage {
    suspend fun saveWebDavCredentials(credentials: StoredWebDavCredentials)
    suspend fun getWebDavCredentials(): StoredWebDavCredentials?
    suspend fun removeWebDavCredentials(): Boolean
}

class DefaultWebDavCredentialStorage(
    private val androidPrefs: SharedPreferences,
    private val cipher: KeyCipher,
) : WebDavCredentialStorage {
    override suspend fun saveWebDavCredentials(credentials: StoredWebDavCredentials) {
        with(androidPrefs.edit()) {
            putString(KEY_SERVER_URL, credentials.serverUrl)
            putString(KEY_USERNAME, cipher.encryptToString(credentials.username))
            putString(KEY_PASSWORD, cipher.encryptToString(credentials.password))
            putBoolean(KEY_ALLOW_INSECURE_HTTP, credentials.allowInsecureHttp)
            apply()
        }
    }

    // A decrypt failure is treated the same as no stored credentials, see DefaultTokenStorage.
    override suspend fun getWebDavCredentials(): StoredWebDavCredentials? {
        val serverUrl = androidPrefs.getString(KEY_SERVER_URL, null) ?: return null
        val username = androidPrefs.getString(KEY_USERNAME, null)
            ?.let { cipher.decryptStringOrNull(it) } ?: return null
        val password = androidPrefs.getString(KEY_PASSWORD, null)
            ?.let { cipher.decryptStringOrNull(it) } ?: return null
        return StoredWebDavCredentials(
            serverUrl,
            username,
            password,
            androidPrefs.getBoolean(KEY_ALLOW_INSECURE_HTTP, false)
        )
    }

    override suspend fun removeWebDavCredentials(): Boolean {
        val existed = androidPrefs.contains(KEY_SERVER_URL)
        with(androidPrefs.edit()) {
            remove(KEY_SERVER_URL)
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
            remove(KEY_ALLOW_INSECURE_HTTP)
            apply()
        }
        return existed
    }

    private companion object {
        const val KEY_SERVER_URL = "WebDav.serverUrl"
        const val KEY_USERNAME = "WebDav.username"
        const val KEY_PASSWORD = "WebDav.password"
        const val KEY_ALLOW_INSECURE_HTTP = "WebDav.allowInsecureHttp"
    }
}
