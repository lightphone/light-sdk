package com.thelightphone.chess

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

class GameStore(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun load(): SavedGame? {
        val raw = dataStore.data.first()[SAVED_GAME] ?: return null
        return runCatching { json.decodeFromString(SavedGame.serializer(), raw) }.getOrNull()
    }

    suspend fun save(game: SavedGame) {
        dataStore.edit { prefs ->
            prefs[SAVED_GAME] = json.encodeToString(SavedGame.serializer(), game)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(SAVED_GAME) }
    }

    companion object {
        private val SAVED_GAME = stringPreferencesKey("saved_game")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
