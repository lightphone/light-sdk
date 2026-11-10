package com.thelightphone.sdk

import android.content.Context

internal object LightAlertStore {
    private const val PREFS = "light_scheduled_alerts"
    private const val KEY = "alerts"

    fun read(context: Context): List<LightAlertRecord> = synchronized(this) {
        decodeAlerts(prefs(context).getString(KEY, "").orEmpty())
    }

    fun update(
        context: Context,
        block: (List<LightAlertRecord>) -> List<LightAlertRecord>,
    ): List<LightAlertRecord> = synchronized(this) {
        val next = block(decodeAlerts(prefs(context).getString(KEY, "").orEmpty()))
        prefs(context).edit().putString(KEY, encodeAlerts(next)).commit()
        next
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
