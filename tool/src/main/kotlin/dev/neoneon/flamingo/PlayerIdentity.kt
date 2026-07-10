package dev.neoneon.flamingo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.util.UUID

private val playerIdKey = stringPreferencesKey("FLAMINGO_PLAYER_ID")

// Superseded by playerIdKey once a real second player can join a game from another
// device — a locally created game is always white, so this reuses whatever identity
// an install already had rather than orphaning games it created before this migration.
private val legacyWhitePlayerIdKey = stringPreferencesKey("FLAMINGO_WHITE_PLAYER_ID")

/** Persists a single player ID per tool installation, generating it on first access. */
class PlayerIdentityStore(private val dataStore: DataStore<Preferences>) {
    suspend fun getOrCreate(): String {
        val current = dataStore.data.first()
        current[playerIdKey]?.let { return it }

        val resolved = current[legacyWhitePlayerIdKey] ?: UUID.randomUUID().toString()
        dataStore.edit { prefs -> prefs[playerIdKey] = resolved }
        return resolved
    }
}
