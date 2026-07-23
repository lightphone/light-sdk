package com.thelightphone.hue

import androidx.datastore.preferences.core.stringPreferencesKey

internal object HuePreferences {
    val BRIDGE_IP = stringPreferencesKey("bridge_ip")
    val BRIDGE_ID = stringPreferencesKey("bridge_id")
    val APP_KEY = stringPreferencesKey("app_key")
}
