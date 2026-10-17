package dev.neoneon.flamingo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Where the hot seat game is kept between launches. There is only ever one, so the port has no
 * ids: load it, replace it, or drop it.
 */
interface HotSeatGameStore {
    suspend fun load(): HotSeatGame?
    suspend fun save(game: HotSeatGame)
    suspend fun clear()
}

private val hotSeatGameKey = stringPreferencesKey("FLAMINGO_HOT_SEAT_GAME")

/** Keeps the hot seat game as JSON in the tool's DataStore, next to the player id and settings. */
class DataStoreHotSeatGameStore(private val dataStore: DataStore<Preferences>) : HotSeatGameStore {
    override suspend fun load(): HotSeatGame? {
        val raw = dataStore.data.first()[hotSeatGameKey] ?: return null
        // Unreadable data would fail the same way every launch, so it goes rather than lingers.
        return decodeHotSeatGame(raw) ?: run {
            clear()
            null
        }
    }

    override suspend fun save(game: HotSeatGame) {
        dataStore.edit { prefs -> prefs[hotSeatGameKey] = encodeHotSeatGame(game) }
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(hotSeatGameKey) }
    }
}
