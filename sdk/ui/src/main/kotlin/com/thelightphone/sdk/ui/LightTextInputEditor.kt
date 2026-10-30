package com.thelightphone.sdk.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import com.thelightphone.lp3Keyboard.textentry.TextEntryAction
import com.thelightphone.lp3Keyboard.textentry.TextEntryMode
import com.thelightphone.lp3Keyboard.textentry.TextEntryPosition
import com.thelightphone.lp3Keyboard.textentry.TextEntryProtocol
import com.thelightphone.lp3Keyboard.textentry.TextEntryRequest
import com.thelightphone.lp3Keyboard.ui.*
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.lp3Keyboard.ui.viewmodel.TextFieldKeyboardCallback
import com.thelightphone.lp3Keyboard.ui.viewmodel.defaultEmojis
import com.thelightphone.sdk.ContextLightVibrator
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val INPUT_UNDERLINE_THICKNESS_PX = 3f
private const val INPUT_UNDERLINE_GAP_GRID_UNITS = 0.5f

/**
 * Full-screen text entry. Opens the external keyboard app when there is one (see
 * [LocalExternalTextEntry]), showing the same title and buttons: its submit and bottom bar items
 * act as they would here, and back (or cancelling) goes back unless [showBackButton] is false.
 * Otherwise, or if a bottom bar item is a [LightBarButton.Icon] the keyboard app can't show, uses
 * the embedded LP3 keyboard.
 */
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
    leftBottomBarItem: LightBottomBarItem? = null,
    rightBottomBarItem: LightBottomBarItem? = null,
    showBackButton: Boolean = true,
    singleLine: Boolean = false,
    initialCaps: Boolean = false,
    editorKey: Any = remember { Any() },
) {
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val currentOnBack by rememberUpdatedState(onBack)
    val currentShowBackButton by rememberUpdatedState(showBackButton)
    val currentLeftItem by rememberUpdatedState(leftBottomBarItem)
    val currentRightItem by rememberUpdatedState(rightBottomBarItem)

    val externalTextEntry = LocalExternalTextEntry.current
    val context = LocalContext.current
    val externalRequest = lightTextEntryRequest(
        context, title, singleLine, showBackButton, submitLabel, submitIcon, leftBottomBarItem, rightBottomBarItem,
    )
    val currentExternalRequest by rememberUpdatedState(externalRequest)
    // go straight to the keyboard app unless we already know it isn't there (or can't show this
    // editor): building the embedded keyboard first would only delay it
    var mode by remember(editorKey, externalTextEntry) {
        mutableStateOf(
            if (externalTextEntry != null && externalTextEntry.isAvailable != false && externalRequest != null) {
                EditorMode.External
            } else {
                EditorMode.Embedded
            }
        )
    }
    LaunchedEffect(editorKey, externalTextEntry, mode) {
        if (mode != EditorMode.External || externalTextEntry == null) return@LaunchedEffect
        val request = currentExternalRequest ?: run {
            mode = EditorMode.Embedded
            return@LaunchedEffect
        }
        when (val result = externalTextEntry.enterText(state.text.toString(), request)) {
            is LightExternalTextEntry.Result.Saved -> {
                val text = if (singleLine) result.text.lines().joinToString(" ") else result.text
                state.setTextAndPlaceCursorAtEnd(text)
                mode = EditorMode.ExternalDone
                when (result.actionId) {
                    LightTextEntryActionIds.SUBMIT -> currentOnSubmit(state.text)
                    LightTextEntryActionIds.LEFT -> currentLeftItem?.onClick?.invoke()
                    LightTextEntryActionIds.RIGHT -> currentRightItem?.onClick?.invoke()
                }
            }
            LightExternalTextEntry.Result.Cancelled -> {
                mode = EditorMode.ExternalDone
                if (currentShowBackButton) currentOnBack()
            }
            LightExternalTextEntry.Result.Unavailable -> mode = EditorMode.Embedded
        }
    }

    when (mode) {
        // the keyboard app is opening or open over us
        EditorMode.External -> Surface { Box(modifier = modifier.fillMaxSize()) }

        // the caller stayed here after a keyboard app session (e.g. its submit is still loading,
        // or it rejected the text): show the text without a keyboard, and tap it to go back
        EditorMode.ExternalDone -> TextInputEditorLayout(
            title = title,
            state = state,
            onSubmit = onSubmit,
            onBack = onBack,
            input = EditorInput.ReopenExternal(onTap = { mode = EditorMode.External }),
            modifier = modifier,
            submitLabel = submitLabel,
            submitIcon = submitIcon,
            leftBottomBarItem = leftBottomBarItem,
            rightBottomBarItem = rightBottomBarItem,
            showBackButton = showBackButton,
            singleLine = singleLine,
        )

        EditorMode.Embedded -> EmbeddedTextInputEditor(
            title = title,
            state = state,
            onSubmit = onSubmit,
            onBack = onBack,
            keyboardOptionsFlow = keyboardOptionsFlow,
            modifier = modifier,
            submitLabel = submitLabel,
            submitIcon = submitIcon,
            leftBottomBarItem = leftBottomBarItem,
            rightBottomBarItem = rightBottomBarItem,
            showBackButton = showBackButton,
            singleLine = singleLine,
            initialCaps = initialCaps,
            editorKey = editorKey,
        )
    }
}

private enum class EditorMode { External, ExternalDone, Embedded }

/** ids of the actions in a [lightTextEntryRequest], which the keyboard app reports the user finished with */
object LightTextEntryActionIds {
    const val SUBMIT = "submit"
    const val LEFT = "left"
    const val RIGHT = "right"
}

/**
 * the keyboard app's version of [LightTextInputEditor] with these options, or null if it can't
 * show it: a [LightBarButton.Icon] is a painter, which can't be handed to another app
 */
fun lightTextEntryRequest(
    context: Context,
    title: String,
    singleLine: Boolean,
    showBackButton: Boolean,
    submitLabel: String,
    submitIcon: LightIconConfiguration?,
    leftBottomBarItem: LightBottomBarItem?,
    rightBottomBarItem: LightBottomBarItem?,
): TextEntryRequest? {
    // the keyboard app reads the icon straight from this app's resources
    fun icon(icon: LightIconConfiguration) = TextEntryAction.Content.CustomIcon(
        Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath(icon.drawableResource.toString())
            .build()
    )

    fun action(id: String, position: TextEntryPosition, item: LightBottomBarItem): TextEntryAction? {
        val content = when (item) {
            is LightBarButton.Text -> TextEntryAction.Content.Text(item.text)
            is LightBarButton.LightIcon -> icon(item.icon)
            is LightBarButton.Icon -> return null
        }
        return TextEntryAction(id, position, content, item.contentDescription)
    }

    val actions = buildList {
        if (showBackButton) {
            add(
                TextEntryAction(
                    TextEntryProtocol.ACTION_ID_CANCEL,
                    TextEntryPosition.TopLeft,
                    icon(LightIcons.BACK),
                    LightIcons.BACK.name,
                )
            )
        }
        add(
            TextEntryAction(
                LightTextEntryActionIds.SUBMIT,
                TextEntryPosition.BottomCenter,
                submitIcon?.let(::icon) ?: TextEntryAction.Content.Text(submitLabel),
                submitLabel,
            )
        )
        leftBottomBarItem?.let { add(action(LightTextEntryActionIds.LEFT, TextEntryPosition.BottomLeft, it) ?: return null) }
        rightBottomBarItem?.let { add(action(LightTextEntryActionIds.RIGHT, TextEntryPosition.BottomRight, it) ?: return null) }
    }
    return TextEntryRequest(
        title = title,
        mode = if (singleLine) TextEntryMode.Simple else TextEntryMode.Multi,
        actions = actions,
        returnActionId = LightTextEntryActionIds.SUBMIT,
    )
}

@Composable
private fun EmbeddedTextInputEditor(
    title: String,
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
    modifier: Modifier,
    submitLabel: String,
    submitIcon: LightIconConfiguration?,
    leftBottomBarItem: LightBottomBarItem?,
    rightBottomBarItem: LightBottomBarItem?,
    showBackButton: Boolean,
    singleLine: Boolean,
    initialCaps: Boolean,
    editorKey: Any,
) {
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val hapticsEnabled = LocalHapticsEnabled.current
    val context = LocalContext.current
    val currentOnHaptic by rememberUpdatedState {
        if (hapticsEnabled) ContextLightVibrator(context).click()
    }
    val keyboardCallback = remember(state, singleLine) {
        TextFieldKeyboardCallback(
            state = state,
            singleLine = singleLine,
            onReturn = { currentOnSubmit(state.text) },
            onHaptic = { currentOnHaptic() },
        )
    }

    val keyboardViewModel: Lp3KeyboardViewModel<*> = viewModel<EnQwertyLp3KeyboardViewModel<*>>(
        key = "LightTextInputEditor-$editorKey",
        factory = factory(keyboardCallback, keyboardOptionsFlow, initialCaps),
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
        leftBottomBarItem,
        rightBottomBarItem,
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
    viewModel: Lp3KeyboardViewModel<*>,
    modifier: Modifier = Modifier,
    submitLabel: String = "SUBMIT",
    submitIcon: LightIconConfiguration? = null,
    leftBottomBarItem: LightBottomBarItem? = null,
    rightBottomBarItem: LightBottomBarItem? = null,
    showBackButton: Boolean = true,
    singleLine: Boolean = false,
) {
    TextInputEditorLayout(
        title = title,
        state = state,
        onSubmit = onSubmit,
        onBack = onBack,
        input = EditorInput.Embedded(viewModel),
        modifier = modifier,
        submitLabel = submitLabel,
        submitIcon = submitIcon,
        leftBottomBarItem = leftBottomBarItem,
        rightBottomBarItem = rightBottomBarItem,
        showBackButton = showBackButton,
        singleLine = singleLine,
    )
}

/** how [TextInputEditorLayout] takes input */
private sealed interface EditorInput {
    /** typing on the embedded LP3 keyboard, with a cursor the user can move by touching the text */
    class Embedded(val viewModel: Lp3KeyboardViewModel<*>) : EditorInput

    /** no keyboard: tapping the text calls [onTap], to reopen the keyboard app */
    class ReopenExternal(val onTap: () -> Unit) : EditorInput
}

@Composable
private fun TextInputEditorLayout(
    title: String,
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    input: EditorInput,
    modifier: Modifier,
    submitLabel: String,
    submitIcon: LightIconConfiguration?,
    leftBottomBarItem: LightBottomBarItem?,
    rightBottomBarItem: LightBottomBarItem?,
    showBackButton: Boolean,
    singleLine: Boolean,
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
                    .then(
                        when (input) {
                            is EditorInput.ReopenExternal -> Modifier.lightClickable(onClick = input.onTap)
                            is EditorInput.Embedded -> Modifier.pointerInput(Unit) {
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
                            }
                        }
                    ),
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
                if (input is EditorInput.Embedded) textLayout?.let { layout ->
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

            val bottomBar = @Composable {
                val submitItem: LightBottomBarItem = when (submitIcon) {
                    null -> LightBarButton.Text(
                        text = submitLabel,
                        onClick = { onSubmit(state.text) },
                    )
                    else -> LightBarButton.LightIcon(
                        icon = submitIcon,
                        onClick = { onSubmit(state.text) },
                        contentDescription = submitLabel,
                    )
                }
                LightBottomBar(
                    items = if (leftBottomBarItem == null && rightBottomBarItem == null) {
                        listOf(submitItem)
                    } else {
                        listOf(leftBottomBarItem, submitItem, rightBottomBarItem)
                    },
                )
            }
            when (input) {
                is EditorInput.Embedded -> LightEmbeddedLp3Keyboard(
                    viewModel = input.viewModel,
                    additionalBottomHeight = 5f.gridUnitsAsDp(),
                    bottomBar = bottomBar,
                )
                is EditorInput.ReopenExternal -> bottomBar()
            }
        }
    }
}

private fun factory(
    callback: Lp3RepeatableKeyboardCallback,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
    initialCaps: Boolean,
): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EnQwertyLp3KeyboardViewModel<Unit>(
                callback,
                keyboardOptionsFlow = keyboardOptionsFlow,
                optionsForLayout = {
                    val showCloseButton = !it.isRootLayout
                    LayoutOptions(showCloseButton)
                },
            ).apply {
                if (initialCaps) setCapsMode(true)
            } as T
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
    displayVoice = false, // dictation not available in LP3 keyboard as of 0.0.19
    enableKeyAnimation = true,
    swipeEnabled = false
)
