package com.thelightphone.sdk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.delay

/**
 * A single volume reading. [token] changes on every key press so the overlay
 * re-shows even when the level is clamped at min/max and hasn't moved.
 */
data class LightVolumeIndicator(val level: Int, val max: Int, val token: Long)

private const val VOLUME_VISIBLE_MS = 1500L

/**
 * Transient volume HUD shown by [LightActivity] when a tool that opted into
 * [useMediaVolumeKeys] adjusts the volume. LightOS's own volume UI does not
 * render over tool windows, so tools draw their own.
 */
@Composable
fun LightVolumeOverlay(indicator: LightVolumeIndicator?) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(indicator?.token) {
        if (indicator != null) {
            visible = true
            delay(VOLUME_VISIBLE_MS)
            visible = false
        }
    }
    if (!visible || indicator == null) return

    val fraction = if (indicator.max > 0) {
        (indicator.level.toFloat() / indicator.max).coerceIn(0f, 1f)
    } else {
        0f
    }
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        val colors = LightThemeTokens.colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 2f.gridUnitsAsDp(),
                    end = 2f.gridUnitsAsDp(),
                    bottom = 2f.gridUnitsAsDp(),
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(0.75f.gridUnitsAsDp()))
                    .background(colors.background)
                    .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightIcon(
                    icon = if (indicator.level == 0) LightIcons.SPEAKER_MUTED else LightIcons.SPEAKER,
                    size = 1.75f,
                    modifier = Modifier.padding(end = 1f.gridUnitsAsDp()),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.contentSecondary),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.content),
                    )
                }
            }
        }
    }
}
