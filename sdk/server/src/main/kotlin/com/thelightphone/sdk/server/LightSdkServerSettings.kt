package com.thelightphone.sdk.server

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.thelightphone.sdk.shared.LightServiceMethod

enum class ClientFilterLevel {
    // LightOS will not show any tools other than the ones baked in by default
    ExcludeAllApks,

    // LightOS will show default tools as well as those curated from the community
    AllowLightApprovedApks,

    // LightOS will show default tools as well as any that have been built as SDK clients and signed using a Light SDK cert
    AllowLightSignedApks,

    // LightOS will show default tools as well as any other APKs that have been installed on the device.
    AllowAllApks
}

class LightSdkServerSettings(context: Context) {

    companion object {
        const val TAG = "LightSdkServerSettings"
        private const val CLIENT_FILTER_LEVEL = "client_filter_level"
        private const val KEYBOARD_EMOJIS = "lp3_keyboard_emojis"
        private const val KEYBOARD_SHOW_VOICE = "lp3_keyboard_show_voice"
        private const val KEYBOARD_ENABLE_KEY_ANIMATION = "lp3_keyboard_enable_key_animation"
    }

    private val contentResolver = context.contentResolver
    private val preferences: SharedPreferences =
        context.getSharedPreferences("light_sdk_server", MODE_PRIVATE)

    var clientFilterLevel: ClientFilterLevel
        get() = preferences
            .getInt(CLIENT_FILTER_LEVEL, ClientFilterLevel.ExcludeAllApks.ordinal)
            .let { index ->
                ClientFilterLevel.entries.getOrElse(index) {
                    Log.e(TAG, "Invalid value for client filter level: $it")
                    ClientFilterLevel.ExcludeAllApks
                }
            }
        set(value) {
            preferences.edit().putInt(CLIENT_FILTER_LEVEL, value.ordinal).apply()
        }

    // store in system settings for now so readable from apps that can't talk to server
    var keyboardOptions: LightServiceMethod.GetKeyboardOptions.Response
        get() {
            return LightServiceMethod.GetKeyboardOptions.Response(
                emojisAsString = Settings.System.getString(contentResolver, KEYBOARD_EMOJIS),
                displayVoice = Settings.System.getInt(contentResolver, KEYBOARD_SHOW_VOICE, 1) == 1,
                enableKeyAnimation = Settings.System.getInt(
                    contentResolver,
                    KEYBOARD_ENABLE_KEY_ANIMATION,
                    1
                ) == 1,
            )
        }
        set(value) {
            Settings.System.putString(contentResolver, KEYBOARD_EMOJIS, value.emojisAsString)
            Settings.System.putInt(
                contentResolver,
                KEYBOARD_SHOW_VOICE,
                if (value.displayVoice) 1 else 0
            )
            Settings.System.putInt(
                contentResolver,
                KEYBOARD_ENABLE_KEY_ANIMATION,
                if (value.enableKeyAnimation) 1 else 0
            )
        }

}