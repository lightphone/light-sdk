package com.thelightphone.sdk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow

sealed interface LightBarButton {
    val onClick: (() -> Unit)?
    val contentDescription: String?

    val enabled: Boolean

    data class Text(
        val text: String,
        override val contentDescription: String? = null,
        override val enabled: Boolean = true,
        override val onClick: (() -> Unit)?,
    ) : LightBarButton

    /**).
     * used for custom icons (your own painter
     *
     * for LightOS icons, prefer [LightBarButton.LightIcon].
     */
    data class Icon(
        val painter: Painter,
        override val onClick: (() -> Unit)?,
        override val contentDescription: String? = null,
        val sizeUnits: Float = LightBarButtonDefaults.ICON_SIZE_UNITS,
        override val enabled: Boolean = true
    ) : LightBarButton

    /**
     * LightOS icon (from [LightIcons]).
     */
    data class LightIcon(
        val icon: LightIconConfiguration,
        override val onClick: (() -> Unit)?,
        override val contentDescription: String? = icon.name,
        val sizeUnits: Float = LightBarButtonDefaults.ICON_SIZE_UNITS,
        override val enabled: Boolean = true
    ) : LightBarButton
}

object LightBarButtonDefaults {
    const val ICON_SIZE_UNITS = 2f
}

typealias LightTopBarButton = LightBarButton
typealias LightBottomBarItem = LightBarButton

@Composable
internal fun LightBarButtonView(
    button: LightBarButton?,
    heightUnits: Float,
    iconSizeUnits: Float = LightBarButtonDefaults.ICON_SIZE_UNITS,
    textVariant: LightTextVariant,
    useSpacerWhenNull: Boolean,
) {
    if (button == null) {
        if (useSpacerWhenNull) {
            LightIcon(
                icon = LightIcons.SPACER,
                size = iconSizeUnits,
                contentDescription = null,
            )
        }
        return
    }

    val baseModifier = Modifier.let { modifier ->
        if (button.onClick != null) {
            modifier.lightClickable(enabled = button.enabled) { button.onClick?.invoke() }
        } else modifier
    }

    when (button) {
        is LightBarButton.Text -> {
            Box(
                modifier = baseModifier.height(heightUnits.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = button.text,
                    variant = textVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lighten = !button.enabled
                )
            }
        }

        is LightBarButton.Icon -> {
            val size = button.sizeUnits.gridUnitsAsDp()
            Image(
                painter = button.painter,
                contentDescription = button.contentDescription,
                contentScale = ContentScale.Fit,
                modifier = baseModifier.size(size),
            )
        }

        is LightBarButton.LightIcon -> {
            LightIcon(
                icon = button.icon,
                size = button.sizeUnits,
                modifier = baseModifier,
                contentDescription = button.contentDescription,
            )
        }
    }
}
