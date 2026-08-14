package com.thelightphone.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview

private const val DEFAULT_TITLE = "Tap"
private const val DEFAULT_PROMPT = "Hold your phone near the other device."
private const val DISABLED_MESSAGE = "Turn on NFC in Settings, then try again."
private const val PERMISSION_MISSING_MESSAGE = "This tool doesn't have access to NFC."
private const val UNAVAILABLE_MESSAGE = "This phone can't use NFC."

enum class LightNfcTapState {
    Unknown,
    Waiting,
    Disabled,
    PermissionMissing,
    Unavailable,
}

@Composable
fun LightNfcTapReader(
    state: LightNfcTapState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = DEFAULT_TITLE,
    prompt: String = DEFAULT_PROMPT,
) {
    val colors = LightThemeTokens.colors
    val message = when (state) {
        LightNfcTapState.Unknown -> ""
        LightNfcTapState.Waiting -> prompt
        LightNfcTapState.Disabled -> DISABLED_MESSAGE
        LightNfcTapState.PermissionMissing -> PERMISSION_MISSING_MESSAGE
        LightNfcTapState.Unavailable -> UNAVAILABLE_MESSAGE
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
            ),
            center = LightTopBarCenter.Text(title),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = message,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightNfcTapReaderWaiting() {
    LightTheme(colors = LightThemeColors.Dark) {
        LightNfcTapReader(
            state = LightNfcTapState.Waiting,
            onBack = {},
        )
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightNfcTapReaderDisabled() {
    LightTheme(colors = LightThemeColors.Dark) {
        LightNfcTapReader(
            state = LightNfcTapState.Disabled,
            onBack = {},
        )
    }
}
