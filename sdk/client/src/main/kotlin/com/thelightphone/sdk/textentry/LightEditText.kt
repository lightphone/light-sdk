package com.thelightphone.sdk.textentry

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBarItem
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalExternalTextEntry
import kotlinx.coroutines.CompletableDeferred

/** how the user finished [SimpleLightScreen.editText] */
sealed interface LightTextEditResult {
    data class Submitted(val text: String) : LightTextEditResult

    /** they hit [item], one of the bottom bar items passed to editText */
    data class BottomBarItem(val text: String, val item: LightBottomBarItem) : LightTextEditResult

    data object Cancelled : LightTextEditResult
}

/** what [SimpleLightScreen.editText] shows, the same options as [LightTextInputEditor] */
internal class TextEditOptions(
    val title: String,
    val text: String,
    val singleLine: Boolean,
    val initialCaps: Boolean,
    val submitLabel: String,
    val submitIcon: LightIconConfiguration?,
    val leftBottomBarItem: LightBottomBarItem?,
    val rightBottomBarItem: LightBottomBarItem?,
    val showBackButton: Boolean,
)

/**
 * [LightTextInputEditor] with the embedded keyboard, on its own screen: [SimpleLightScreen.editText]
 * when there's no keyboard app. Completes [finished] when it closes, however that happens.
 */
internal class TextEditorScreen(
    sealedActivity: SealedLightActivity,
    private val options: TextEditOptions,
    private val finished: CompletableDeferred<LightTextEditResult>,
) : SimpleLightScreen<LightTextEditResult>(sealedActivity) {

    private fun finish(outcome: LightTextEditResult) {
        finished.complete(outcome)
        goBack(outcome)
    }

    // e.g. system back, which pops the screen without a result
    override fun onScreenDestroy() {
        finished.complete(LightTextEditResult.Cancelled)
    }

    @Composable
    override fun Content() {
        val state = rememberTextFieldState(options.text)
        val keyboardOptions = rememberKeyboardOptions()
        val themeColors by LightThemeController.colors.collectAsState()

        // the bottom bar items finish the edit, as they do in the keyboard app
        fun LightBottomBarItem.finishing(): LightBottomBarItem {
            val onClick = { finish(LightTextEditResult.BottomBarItem(state.text.toString(), this)) }
            return when (this) {
                is LightBarButton.Text -> copy(onClick = onClick)
                is LightBarButton.Icon -> copy(onClick = onClick)
                is LightBarButton.LightIcon -> copy(onClick = onClick)
            }
        }

        LightTheme(colors = themeColors) {
            // editText only gets here when the keyboard app isn't an option
            CompositionLocalProvider(LocalExternalTextEntry provides null) {
                LightTextInputEditor(
                    title = options.title,
                    state = state,
                    onSubmit = { finish(LightTextEditResult.Submitted(it.toString())) },
                    onBack = { finish(LightTextEditResult.Cancelled) },
                    keyboardOptionsFlow = keyboardOptions,
                    modifier = Modifier.background(LightThemeTokens.colors.background),
                    submitLabel = options.submitLabel,
                    submitIcon = options.submitIcon,
                    leftBottomBarItem = options.leftBottomBarItem?.finishing(),
                    rightBottomBarItem = options.rightBottomBarItem?.finishing(),
                    showBackButton = options.showBackButton,
                    singleLine = options.singleLine,
                    initialCaps = options.initialCaps,
                )
            }
        }
    }
}
