package com.thelightphone.uidemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.viewmodel.defaultEmojis
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EditorRequest(
    val title: String,
    val initialValue: String,
    val initialCaps: Boolean = false,
)

class UiDemoTextInputEditorScreen(
    sealedActivity: SealedLightActivity,
    private val editorRequest: EditorRequest
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val keyboardOptionsFlow = rememberKeyboardOptions()
        ContentInner(keyboardOptionsFlow, editorRequest, this::goBack)
    }
}

@Composable
private fun ContentInner(
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
    editorRequest: EditorRequest,
    goBack: (String?) -> Unit
) {
    val textState = rememberTextFieldState(editorRequest.initialValue)
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        LightTextInputEditor(
            title = editorRequest.title,
            state = textState,
            keyboardOptionsFlow = keyboardOptionsFlow,
            onSubmit = { result -> goBack(result.toString()) },
            onBack = { goBack(null) },
            modifier = Modifier.background(LightThemeTokens.colors.background),
            initialCaps = editorRequest.initialCaps,
        )
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun TextEditorPreview() {
    val editorRequest = EditorRequest("Text Input", "Hello")
    val keyboardOptionsFlow = MutableStateFlow(
        KeyboardOptions(
            defaultEmojis,
            displayReturn = true,
            displayVoice = true,
            enableKeyAnimation = true,
            swipeEnabled = true
        )
    )
    Surface {
        Box(Modifier.fillMaxSize()) {
            ContentInner(keyboardOptionsFlow, editorRequest) { }
        }
    }
}
