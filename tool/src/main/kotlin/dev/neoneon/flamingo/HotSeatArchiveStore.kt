package dev.neoneon.flamingo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Where finished hot seat games are kept once they have been saved to the archive. Mirrors iOS's
 * `HotSeatArchiveStore`, except that it is read as a flow: the archive screen follows it, so a
 * deletion made on the replay screen shows without a reload that could race the write.
 */
interface HotSeatArchiveStore {
    /** Every archived game, the most recently archived first. */
    val games: Flow<List<ArchivedHotSeatGame>>

    /** Puts [game] at the top of the archive, replacing an earlier copy with the same id. */
    suspend fun add(game: ArchivedHotSeatGame)

    suspend fun remove(id: String)
}

private val hotSeatArchiveKey = stringPreferencesKey("FLAMINGO_HOT_SEAT_ARCHIVE")

/**
 * Keeps the archive as one JSON list in the tool's DataStore, next to the hot seat game. Unreadable
 * data reads as an empty archive, and the next save writes over it.
 */
class DataStoreHotSeatArchiveStore(private val dataStore: DataStore<Preferences>) : HotSeatArchiveStore {
    override val games: Flow<List<ArchivedHotSeatGame>> =
        dataStore.data.map { prefs -> decodeHotSeatArchive(prefs[hotSeatArchiveKey]).orEmpty() }

    override suspend fun add(game: ArchivedHotSeatGame) = update { it.adding(game) }

    override suspend fun remove(id: String) = update { it.removing(id) }

    // Read and written in one edit, so two changes can't both start from the same list and lose one.
    private suspend fun update(change: (List<ArchivedHotSeatGame>) -> List<ArchivedHotSeatGame>) {
        dataStore.edit { prefs ->
            val games = decodeHotSeatArchive(prefs[hotSeatArchiveKey]).orEmpty()
            prefs[hotSeatArchiveKey] = encodeHotSeatArchive(change(games))
        }
    }
}
