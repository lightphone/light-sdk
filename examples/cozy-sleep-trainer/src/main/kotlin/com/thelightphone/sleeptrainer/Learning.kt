package com.thelightphone.sleeptrainer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ----- MODELS ----- //

@Serializable
data class SessionData(
    val totalTimeSeconds: Int,
    val date: String,
    val intervalsCompleted: Int = 0
)

// ----- REPOSITORY ----- // [Handles saving/loading history data]

object HistoryRepository {
    private val HISTORY_KEY = stringPreferencesKey("history")

    suspend fun loadHistory(dataStore: DataStore<Preferences>): List<SessionData> {
        val json = dataStore.data.first()[HISTORY_KEY] ?: "[]"
        return try {
            Json.decodeFromString<List<SessionData>>(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveSession(
        dataStore: DataStore<Preferences>,
        totalSeconds: Int,
        intervalsCompleted: Int
    ): List<SessionData> {
        // Don't save very short sessions
        if (totalSeconds < 5) return loadHistory(dataStore)

        val date = SimpleDateFormat("MMM dd", Locale.US).format(Date())
        val currentHistory = loadHistory(dataStore).toMutableList()

        val newSession = SessionData(
            totalTimeSeconds = totalSeconds,
            date = date,
            intervalsCompleted = intervalsCompleted
        )

        currentHistory.add(0, newSession)
        val updatedHistory = currentHistory.take(10)
        val json = Json.encodeToString(updatedHistory)

        dataStore.edit { prefs ->
            prefs[HISTORY_KEY] = json
        }
        return updatedHistory
    }
}

// ----- SCHEDULE ----- // [The "rules" for the training]

object FerberSchedule {
    private val intervals = listOf(2, 5, 10, 15)

    /** 
     * Returns the interval length in minutes.
     * If the index goes beyond our list, we stay at the final 15-minute interval.
     */
    fun getIntervalMinutes(index: Int): Int {
        return if (index < intervals.size) {
            intervals[index]
        } else {
            15 // This is the "infinite 15-minute logic"
        }
    }

    fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }
}
