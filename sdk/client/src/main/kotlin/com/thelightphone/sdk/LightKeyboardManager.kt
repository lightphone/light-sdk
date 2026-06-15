package com.thelightphone.sdk

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.defaultEmojis
import com.thelightphone.lp3Keyboard.ui.parseEmojiString
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.error
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "LightKeyboardManager"

suspend fun refreshKeyboardOptions(): KeyboardOptions? {
    val result =
        callRemoteServiceMethod(LightServiceMethod.GetKeyboardOptions, Unit).let { result ->
            result.error?.let {
                Log.e(TAG, "Error getting keyboard options, code:${it.code}, message:${it.extra}")
                return null
            }
            val options = result.getOrNull()
            if (options == null) {
                Log.e(TAG, "Keyboard options returned null")
                return null
            }
            options
        }

    return KeyboardOptions(
        emojis = parseEmojiString(result.emojisAsString) ?: defaultEmojis,
        // not using currently - may want to move into LayoutOptions in Lp3Keyboard source
        displayReturn = true,
        displayVoice = result.displayVoice,
        enableKeyAnimation = result.enableKeyAnimation
    )
}

private var cachedOptions = defaultKeyboardOptions()

@Composable
fun rememberKeyboardOptions(
    initialOptions: KeyboardOptions = cachedOptions
): StateFlow<KeyboardOptions> {
    val flow = remember { MutableStateFlow(initialOptions) }
    val scope = rememberCoroutineScope()
    val refreshJob = remember { mutableStateOf<Job?>(null) }

    SideEffect {
        refreshJob.value?.cancel()
        refreshJob.value = scope.launch {
            refreshKeyboardOptions()?.let {
                cachedOptions = it
                flow.value = it
            }
        }
    }
    return flow
}