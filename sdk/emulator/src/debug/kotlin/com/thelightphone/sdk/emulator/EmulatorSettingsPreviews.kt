package com.thelightphone.sdk.emulator

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.thelightphone.sdk.emulator.backup.rememberPreviewBackupSettingsViewModel
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.ForceFocusLevel
import com.thelightphone.sdk.server.LightSdkServerSettings
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun EmulatorSettingsPreview() {
    val settings = object : LightSdkServerSettings {
        override var clientFilterLevel: ClientFilterLevel = ClientFilterLevel.AllowAllApks
        override var keyboardOptions: LightServiceMethod.GetKeyboardOptions.Response =
            LightServiceMethod.GetKeyboardOptions.Response(
                null,
                displayVoice = true,
                enableKeyAnimation = true,
                swipeEnabled = true
            )
        override var userPreferences: LightServiceMethod.GetUserPreferences.Response =
            LightServiceMethod.GetUserPreferences.Response(hapticsEnabled = true)
        override var forceFocusLevel: ForceFocusLevel = ForceFocusLevel.Always
    }
    val emulatorAudioWrapper = object : EmulatorSettingsAudio {
        private val currentVolume = MutableStateFlow(0.5f)
        override fun setRingerVolume(normalized: Float) {
            currentVolume.value = normalized
        }

        override val ringerVolume: StateFlow<Float> = currentVolume.asStateFlow()
    }

    LightTheme {
        EmulatorSettings(
            settings,
            emulatorAudioWrapper,
            Nav.Settings(),
            rememberPreviewBackupSettingsViewModel(),
        ) { }
    }
}
