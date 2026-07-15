package com.thelightphone.sdk.emulator

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.thelightphone.sdk.shared.LightKeys
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CompletableDeferred

class EmulatorDeviceKeyHandler(private val audioManager: LightAudioManager) {

    companion object {
        private const val TAG = "EmulatorDeviceKeyHandler"
    }

    fun onDeviceKeyEventRequest(request: LightServiceMethod.DeviceKeyEvent.Request): AudioModal? {
        if (request.eventType != LightServiceMethod.DeviceKeyEvent.EventType.KeyDown) return null
        return when (request.keyCode) {
            LightKeys.VOLUME_UP -> audioManager.stepUp()
            LightKeys.VOLUME_DOWN -> audioManager.stepDown()
            else -> {
                Log.d(TAG, "Unhandled keyCode: ${request.keyCode}")
                null
            }
        }
    }
}

class DeviceKeyModal(
    private val audioModal: AudioModal,
    private val onMoreClick: () -> Unit,
    override val onExpired: () -> Unit
) : LightModal {
    private val dismissDeferred = CompletableDeferred<Unit>()

    override fun dismiss() { dismissDeferred.complete(Unit) }
    override suspend fun awaitDismiss() { dismissDeferred.await() }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val label = when (audioModal.type) {
            ModalType.RingerVol -> "ringer"
            ModalType.CallVol -> "call volume"
            ModalType.AlarmVol -> "alarm"
            ModalType.MediaVol -> "volume"
            ModalType.Silent -> "silent"
            ModalType.VibrateOnly -> "vibrate only"
        }
        LightTheme(themeColors) {
            Surface {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 3.5f.gridUnitsAsDp())
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Spacer(Modifier.height(10.5f.gridUnitsAsDp()))
                        LightText(label, variant = LightTextVariant.Subtitle)
                        if (audioModal.type == ModalType.Silent) {
                            Spacer(Modifier.height(2f.gridUnitsAsDp()))
                            LightIcon(LightIcons.SPEAKER_MUTED)
                        } else {
                            Spacer(Modifier.height(2.5f.gridUnitsAsDp()))
                            LightProgressBar(themeColors, audioModal.value)
                        }
                    }
                    LightText(
                        text = ". . .",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.lightClickable {
                            dismiss()
                            onMoreClick()
                        }
                    )
                    Spacer(Modifier.height(1f.gridUnitsAsDp()))
                }
            }
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun DeviceKeyModalPreview() {
    var audioModal by remember { mutableStateOf(AudioModal(ModalType.RingerVol, 0.4f)) }
    fun toggle() {
        audioModal = if (audioModal.type == ModalType.RingerVol) {
            AudioModal(ModalType.Silent, 0.0f)
        } else {
            AudioModal(ModalType.RingerVol, 0.4f)
        }
    }
    DeviceKeyModal(audioModal, onExpired = {}, onMoreClick = ::toggle).Content()
}
