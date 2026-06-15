package com.thelightphone.sdk.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.lp3Keyboard.ui.DarkKeyboardColors
import com.thelightphone.lp3Keyboard.ui.LightKeyboardColors
import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardTheme
import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.LocalKeyboardColors
import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardWrapper
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens

@Composable
fun LightEmbeddedLp3Keyboard(viewModel: Lp3KeyboardViewModel) {
    val layout by viewModel.layoutFlow.collectAsState()
    val keyboardOptions by viewModel.keyboardOptionsFlow.collectAsState()
    val layoutOptions by viewModel.layoutOptionsFlow.collectAsState()

    val keyboardColors = if (LightThemeTokens.colors == LightThemeColors.Light) {
        LightKeyboardColors
    } else {
        DarkKeyboardColors
    }
    Lp3KeyboardTheme(keyboardColors) {
        val colors = LocalKeyboardColors.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(top = 10.dp),
        ) {
            Lp3KeyboardWrapper(
                layout = layout,
                keyboardOptions = keyboardOptions,
                layoutOptions = layoutOptions,
                callback = viewModel,
            )
        }
    }
}
