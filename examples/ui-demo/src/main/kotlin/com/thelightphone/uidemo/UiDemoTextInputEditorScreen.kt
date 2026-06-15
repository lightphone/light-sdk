package com.thelightphone.uidemo

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

class UiDemoTextInputEditorViewModel : LightViewModel<String>()

data class EditorRequest(
    val title: String,
    val initialValue: String,
)

class UiDemoTextInputEditorScreen(
    sealedActivity: SealedLightActivity,
    private val editorRequest: EditorRequest
) : LightScreen<String, UiDemoTextInputEditorViewModel>(sealedActivity) {

    override val viewModelClass: Class<UiDemoTextInputEditorViewModel>
        get() = UiDemoTextInputEditorViewModel::class.java

    override val showBackBar: Boolean = false

    override fun createViewModel() = UiDemoTextInputEditorViewModel()

    @Composable
    override fun Content() {
        val textState = rememberTextFieldState(editorRequest.initialValue)
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()
        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = editorRequest.title,
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                onSubmit = { result -> goBack(result.toString()) },
                onBack = { goBack(null) },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
