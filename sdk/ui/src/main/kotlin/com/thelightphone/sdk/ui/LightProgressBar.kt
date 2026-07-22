package com.thelightphone.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun LightProgressBar(colors: LightColors, progress: Float) {
    Box(contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.1f.gridUnitsAsDp())
                .background(colors.content)
        )
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(0.5f.gridUnitsAsDp())
                .background(colors.content)
        )
    }
}

@Composable
fun LightTouchableProgressBar(
    colors: LightColors,
    progress: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier.height(3f.gridUnitsAsDp()),
    ) {
        val trackWidthPx = with(density) { maxWidth.toPx() }

        fun xToProgress(xPx: Float) = (xPx / trackWidthPx).coerceIn(0f, 1f)

        LightProgressBar(colors, progress)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        currentOnValueChange(xToProgress(down.position.x))
                        drag(down.id) { change ->
                            change.consume()
                            currentOnValueChange(xToProgress(change.position.x))
                        }
                    }
                }
                }
        )
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun LightProgressBarPreview() {
    val colors = LightThemeColors.Dark
    var progress by remember { mutableFloatStateOf(0.4f) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 3.5f.gridUnitsAsDp())
    ) {
        Box(Modifier.fillMaxWidth()) {
            LightTouchableProgressBar(colors, progress, onValueChange = { progress = it })
        }
    }
}