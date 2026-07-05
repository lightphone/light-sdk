package dev.neoneon.flamingo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.util.UUID

private val whitePlayerIdKey = stringPreferencesKey("FLAMINGO_WHITE_PLAYER_ID")
private val blackPlayerIdKey = stringPreferencesKey("FLAMINGO_BLACK_PLAYER_ID")

/** The two local player identities this tool installation plays as, one per color. */
data class PlayerIdentity(val whitePlayerId: String, val blackPlayerId: String)

/** Persists [PlayerIdentity] once per tool installation, generating it on first access. */
class PlayerIdentityStore(private val dataStore: DataStore<Preferences>) {
    suspend fun getOrCreate(): PlayerIdentity {
        val current = dataStore.data.first()
        val existingWhite = current[whitePlayerIdKey]
        val existingBlack = current[blackPlayerIdKey]
        if (existingWhite != null && existingBlack != null) {
            return PlayerIdentity(existingWhite, existingBlack)
        }

        val resolved = PlayerIdentity(
            whitePlayerId = existingWhite ?: UUID.randomUUID().toString(),
            blackPlayerId = existingBlack ?: UUID.randomUUID().toString(),
        )
        dataStore.edit { prefs ->
            prefs[whitePlayerIdKey] = resolved.whitePlayerId
            prefs[blackPlayerIdKey] = resolved.blackPlayerId
        }
        return resolved
    }
}
