package com.thelightphone.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.*
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.keyboard.TextInputKeyboardCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val INPUT_UNDERLINE_THICKNESS_PX = 3f
private const val INPUT_UNDERLINE_GAP_GRID_UNITS = 0.5f

@Composable
fun LightTextInputEditor(
    title: String,
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
    modifier: Modifier = Modifier,
    submitLabel: String = "SUBMIT",
    submitIcon: LightIconConfiguration? = null,
    showBackButton: Boolean = true,
    singleLine: Boolean = false,
    editorKey: Any = title,
) {
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val keyboardCallback = remember(state, singleLine) {
        TextInputKeyboardCallback(
            state = state,
            singleLine = singleLine,
            onReturn = { currentOnSubmit(state.text) },
        )
    }

    val keyboardViewModel: Lp3KeyboardViewModel = viewModel<DefaultLp3KeyboardViewModel>(
        key = "LightTextInputEditor-$editorKey",
        factory = factory(keyboardCallback, keyboardOptionsFlow),
    )

    LightTextInputEditor(
        title,
        state,
        onSubmit,
        onBack,
        keyboardViewModel,
        modifier,
        submitLabel,
        submitIcon,
        showBackButton,
        singleLine,
    )
}

/**
 * Full-screen text entry matching LightOS `DisplayWithKeyboardPortrait`
 *
 * - Top bar with back button + title
 * - Remaining space shows underlined heading-style input (top-aligned)
 * - Embedded LP3 keyboard, and [LightBottomBar] below it
 */
@Composable
fun LightTextInputEditor(
    title: String,
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    viewModel: Lp3KeyboardViewModel,
    modifier: Modifier = Modifier,
    submitLabel: String = "SUBMIT",
    submitIcon: LightIconConfiguration? = null,
    showBackButton: Boolean = true,
    singleLine: Boolean = false,
) {
    val colors = LightThemeTokens.colors
    val inputStyle = lightInputTextStyle()
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Surface {
        Column(modifier = modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = if (showBackButton) {
                    LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = onBack,
                    )
                } else {
                    null
                },
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp())
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            textLayout?.let { layout ->
                                state.edit {
                                    selection =
                                        TextRange(layout.getOffsetForPosition(down.position))
                                }
                            }
                            drag(down.id) { change ->
                                textLayout?.let { layout ->
                                    state.edit {
                                        selection =
                                            TextRange(layout.getOffsetForPosition(change.position))
                                    }
                                }
                                change.consume()
                            }
                        }
                    },
                contentAlignment = Alignment.TopStart,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    BasicText(
                        text = state.text.toString(),
                        style = inputStyle,
                        onTextLayout = { textLayout = it },
                        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                        softWrap = !singleLine,
                        overflow = if (singleLine) TextOverflow.StartEllipsis else TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(
                        modifier = Modifier.height(
                            INPUT_UNDERLINE_GAP_GRID_UNITS.gridUnitsAsDp(),
                        ),
                    )
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(INPUT_UNDERLINE_THICKNESS_PX.designVerticalPxToDp())
                            .background(colors.content),
                    )
                }
                textLayout?.let { layout ->
                    val cursorPos = state.selection.min.coerceIn(0, layout.layoutInput.text.length)
                    val rect = layout.getCursorRect(cursorPos)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                            .width(2.dp)
                            .height(with(LocalDensity.current) { rect.height.toDp() })
                            .background(colors.content),
                    )
                }
            }

            LightEmbeddedLp3Keyboard(viewModel = viewModel)

            LightBottomBar(
                items = listOf(
                    when (val icon = submitIcon) {
                        null -> LightBarButton.Text(
                            text = submitLabel,
                            onClick = { onSubmit(state.text) },
                        )
                        else -> LightBarButton.LightIcon(
                            icon = icon,
                            onClick = { onSubmit(state.text) },
                            contentDescription = submitLabel,
                        )
                    },
                ),
            )
        }
    }
}

private fun factory(
    callback: Lp3RepeatableKeyboardCallback,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>
): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DefaultLp3KeyboardViewModel(
                callback,
                keyboardOptionsFlow = keyboardOptionsFlow,
                optionsForLayout = {
                    val showCloseButton = when (it) {
                        EmojiLayout, is ExtendedCharKeyboard -> true
                        CapsLockedLayout, LowerCaseLayout, NumberLayout, SymbolsLayout, UpperCaseLayout -> false
                    }
                    LayoutOptions(showCloseButton)
                }
            ) as T
        }

    }

@Composable
private fun lightInputTextStyle(): TextStyle {
    val colors = LightThemeTokens.colors
    val t = LightThemeTokens.typography
    return t.heading
        .copy(
            color = colors.content,
        )
        .scaledForScreenHeight()
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightTextInputEditorDark() {
    val state = rememberTextFieldState("hi")
    LightTheme(colors = LightThemeColors.Dark) {
        LightTextInputEditor(
            title = "Name",
            state = state,
            keyboardOptionsFlow = MutableStateFlow(defaultKeyboardOptions()),
            onSubmit = {},
            onBack = {},
        )
    }
}

fun defaultKeyboardOptions() = KeyboardOptions(
    defaultEmojis,
    displayReturn = true,
    displayVoice = true,
    enableKeyAnimation = true
)
