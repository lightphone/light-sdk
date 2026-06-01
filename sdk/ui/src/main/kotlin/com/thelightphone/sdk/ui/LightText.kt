package com.thelightphone.sdk.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow

/**
 * UIText equivalent for the Light SDK
 *
 * - variant-based typography
 * - primary/secondary (lighten) color
 * - optional underline + alignment
 */
enum class LightTextVariant {
    Title,
    Subtitle,
    Heading,
    Subheading,
    Copy,
    Button,
    Paragraph,
    ParagraphWide,
    Detail,
    Fine,
    Superfine,
    Micro,
}

@Composable
private fun variantStyle(variant: LightTextVariant): TextStyle {
    val t = LightThemeTokens.typography
    val base = when (variant) {
        LightTextVariant.Title -> t.title
        LightTextVariant.Subtitle -> t.subtitle
        LightTextVariant.Heading -> t.heading
        LightTextVariant.Subheading -> t.subheading
        LightTextVariant.Copy -> t.copy
        LightTextVariant.Button -> t.button
        LightTextVariant.Paragraph -> t.paragraph
        LightTextVariant.ParagraphWide -> t.paragraphWide
        LightTextVariant.Detail -> t.detail
        LightTextVariant.Fine -> t.fine
        LightTextVariant.Superfine -> t.superfine
        LightTextVariant.Micro -> t.micro
    }
    return base.scaledForScreenHeight()
}

@Composable
private fun TextStyle.scaledForScreenHeight(): TextStyle {
    val fontSize = fontSize.scaledForScreenHeight()
    val lineHeight = lineHeight.scaledForScreenHeight()
    val letterSpacing = letterSpacing.scaledForScreenHeight()
    return copy(
        fontSize = fontSize,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
    )
}

@Composable
private fun TextUnit.scaledForScreenHeight(): TextUnit {
    if (this == TextUnit.Unspecified) return this
    return value.designVerticalPxToSp()
}

@Composable
fun LightText(
    text: String,
    variant: LightTextVariant,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    lighten: Boolean = false,
    underline: Boolean = false,
    monospace: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    color: Color? = null,
) {
    val colors = LightThemeTokens.colors
    val baseColor = when {
        color != null -> color
        lighten -> colors.contentSecondary
        else -> colors.content
    }

    val style = variantStyle(variant)
        .let { if (align != null) it.copy(textAlign = align) else it }
        .let { if (underline) it.copy(textDecoration = TextDecoration.Underline) else it }
        .let { if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it }

    Text(
        text = text,
        modifier = modifier,
        color = baseColor.takeUnless { it == Color.Unspecified } ?: LocalContentColor.current,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
    )
}

