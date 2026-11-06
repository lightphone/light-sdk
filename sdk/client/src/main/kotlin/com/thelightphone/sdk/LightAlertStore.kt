package com.thelightphone.sdk

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object LightAlertStore {
    private const val PREFS = "light_scheduled_alerts"
    private const val KEY = "alerts"

    // Every write goes through update, so this stays current once it is created.
    private var ids: MutableStateFlow<Set<String>>? = null

    fun ids(context: Context): StateFlow<Set<String>> = synchronized(this) {
        val flow = ids ?: MutableStateFlow(read(context).idSet()).also { ids = it }
        flow.asStateFlow()
    }

    fun read(context: Context): List<LightAlertRecord> = synchronized(this) {
        decodeAlerts(prefs(context).getString(KEY, "").orEmpty())
    }

    fun update(
        context: Context,
        block: (List<LightAlertRecord>) -> List<LightAlertRecord>,
    ): List<LightAlertRecord> = synchronized(this) {
        val next = block(decodeAlerts(prefs(context).getString(KEY, "").orEmpty()))
        prefs(context).edit().putString(KEY, encodeAlerts(next)).commit()
        ids?.value = next.idSet()
        next
    }

    private fun List<LightAlertRecord>.idSet(): Set<String> = mapTo(mutableSetOf()) { it.id }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
