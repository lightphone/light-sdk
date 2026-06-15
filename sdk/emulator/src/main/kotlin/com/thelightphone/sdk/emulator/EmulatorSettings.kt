package com.thelightphone.sdk.emulator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.server.ClientFilterLevel
import com.thelightphone.sdk.server.LightSdkServerSettings
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBarButton.*
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightTopBarCenter.*
import com.thelightphone.sdk.ui.gridUnitsAsDp

private enum class EmulatorSettingsNav {
    Root, FilterLevel, Keyboard
}

val ClientFilterLevel.label: String
    get() = when (this) {
        ClientFilterLevel.ExcludeAllApks -> "Default Only"
        ClientFilterLevel.AllowLightApprovedApks -> "Community Tools"
        ClientFilterLevel.AllowLightSignedApks -> "Built with SDK"
        ClientFilterLevel.AllowAllApks -> "All Tools"
    }

@Composable
fun EmulatorSettings(settings: LightSdkServerSettings, onBackPressed: () -> Unit) {
    var nav by remember { mutableStateOf(EmulatorSettingsNav.Root) }
    Surface(Modifier.fillMaxSize()) {
        when (nav) {
            EmulatorSettingsNav.Root -> {
                Column(Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightIcon(
                            icon = LightIcons.BACK,
                            onClick = onBackPressed
                        ),
                        center = LightTopBarCenter.Text("Settings"),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav = EmulatorSettingsNav.FilterLevel }
                            .padding(16.dp)
                    ) {
                        Column {
                            LightText("Allowed Tools", variant = LightTextVariant.Superfine)
                            LightText(
                                settings.clientFilterLevel.label,
                                variant = LightTextVariant.Subheading
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav = EmulatorSettingsNav.Keyboard }
                            .padding(16.dp)
                    ) {
                        Column {
                            LightText(
                                "Keyboard Settings",
                                variant = LightTextVariant.Subheading
                            )
                        }
                    }
                }
            }

            EmulatorSettingsNav.FilterLevel -> ClientFilterLevelSettings(settings) {
                nav = EmulatorSettingsNav.Root
            }

            EmulatorSettingsNav.Keyboard -> KeyboardSettings(settings) {
                nav = EmulatorSettingsNav.Root
            }
        }
    }
}

@Composable
fun ClientFilterLevelSettings(settings: LightSdkServerSettings, onBackPressed: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBackPressed
                ),
                center = LightTopBarCenter.Text("Allowed Tools"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            for (clientFilterLevel in ClientFilterLevel.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            settings.clientFilterLevel = clientFilterLevel
                            onBackPressed()
                        }
                        .padding(16.dp)
                ) {
                    LightText(
                        clientFilterLevel.label,
                        variant = LightTextVariant.Subheading
                    )
                }
            }
        }
    }
}

@Composable
fun KeyboardSettings(settings: LightSdkServerSettings, onBackPressed: () -> Unit) {
    var keyboardOptions by remember { mutableStateOf(settings.keyboardOptions) }
    fun updateOptions(newOptions: LightServiceMethod.GetKeyboardOptions.Response) {
        settings.keyboardOptions = newOptions
        keyboardOptions = newOptions
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBackPressed
                ),
                center = LightTopBarCenter.Text("Keyboard Settings"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            Column(Modifier.padding(horizontal = 16.dp)) {
                LightText(
                    text = when (keyboardOptions.enableKeyAnimation) {
                        true -> "KEY ANIMATION: ON"
                        false -> "KEY ANIMATION: OFF"
                    },
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .clickable {
                            updateOptions(keyboardOptions.copy(enableKeyAnimation = !keyboardOptions.enableKeyAnimation))
                        }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )

                LightText(
                    text = when (keyboardOptions.displayVoice) {
                        true -> "SHOW VOICE KEY: ON"
                        false -> "SHOW VOICE KEY: OFF"
                    },
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .clickable {
                            updateOptions(keyboardOptions.copy(displayVoice = !keyboardOptions.displayVoice))
                        }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )
            }
        }
    }
}