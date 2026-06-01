package com.thelightphone.sdk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.zIndex

private const val TOPBAR_HEIGHT_UNITS = 3f
private const val HORIZONTAL_PADDING_UNITS = 1f
private const val CENTER_MAX_WIDTH_UNITS = 18f

private val TOPBAR_BUTTON_TEXT_VARIANT = LightTextVariant.Fine
private val TOPBAR_CENTER_TEXT_VARIANT = LightTextVariant.Fine

sealed interface LightTopBarCenter {
    val onClick: (() -> Unit)?

    data class Text(
        val text: String,
        override val onClick: (() -> Unit)? = null,
    ) : LightTopBarCenter

    data class TwoLineDetail(
        val line1: String,
        val line2: String,
        override val onClick: (() -> Unit)? = null,
    ) : LightTopBarCenter
}

@Composable
fun LightTopBar(
    leftButton: LightTopBarButton? = null,
    center: LightTopBarCenter? = null,
    rightButton: LightTopBarButton? = null,
    modifier: Modifier = Modifier,
) {
    val barHeight = TOPBAR_HEIGHT_UNITS.gridUnitsAsDp()
    val horizontalPadding = HORIZONTAL_PADDING_UNITS.gridUnitsAsDp()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .padding(horizontal = horizontalPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .zIndex(2f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.height(barHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                LightBarButtonView(
                    button = leftButton,
                    heightUnits = TOPBAR_HEIGHT_UNITS,
                    textVariant = TOPBAR_BUTTON_TEXT_VARIANT,
                    useSpacerWhenNull = true,
                )
            }

            Box(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier.height(barHeight),
                contentAlignment = Alignment.CenterEnd,
            ) {
                LightBarButtonView(
                    button = rightButton,
                    heightUnits = TOPBAR_HEIGHT_UNITS,
                    textVariant = TOPBAR_BUTTON_TEXT_VARIANT,
                    useSpacerWhenNull = true,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
            contentAlignment = Alignment.Center,
        ) {
            LightTopBarCenterView(center = center)
        }
    }
}

@Composable
private fun LightTopBarCenterView(center: LightTopBarCenter?) {
    if (center == null) return

    val modifier = Modifier
        .widthIn(max = CENTER_MAX_WIDTH_UNITS.gridUnitsAsDp())
        .let { m -> if (center.onClick != null) m.clickable { center.onClick?.invoke() } else m }

    when (center) {
        is LightTopBarCenter.Text -> {
            LightText(
                text = center.text,
                variant = TOPBAR_CENTER_TEXT_VARIANT,
                align = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier,
            )
        }

        is LightTopBarCenter.TwoLineDetail -> {
            Columnish(modifier = modifier) {
                LightText(
                    text = center.line1,
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = center.line2,
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Columnish(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightTopBarDark() {
    LightTheme(colors = LightThemeColors.Dark) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = {},
            ),
            center = LightTopBarCenter.Text("Title"),
            rightButton = LightBarButton.Text("EDIT", onClick = {}),
        )
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightTopBarLight() {
    LightTheme(colors = LightThemeColors.Light) {
        LightTopBar(
            leftButton = LightBarButton.Text("BACK", onClick = {}),
            center = LightTopBarCenter.TwoLineDetail("Line 1", "Line 2"),
            rightButton = LightBarButton.LightIcon(
                icon = LightIcons.SETTINGS,
                onClick = {},
            ),
        )
    }
}
