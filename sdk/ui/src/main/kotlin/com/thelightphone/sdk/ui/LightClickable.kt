package com.thelightphone.sdk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role

/**
 * Will be periodically set based on server preferences, see:
 * [com.thelightphone.sdk.shared.LightServiceMethod.GetUserPreferences].
 */
val LocalHapticsEnabled = compositionLocalOf { false }

/**
 * Makes content clickable without displaying a visual press indication.
 */
fun Modifier.lightClickable(
    enabled: Boolean = true,
    // will be &&'ed with the user's global haptics preference
    hapticsEnabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val userHapticsEnabled = LocalHapticsEnabled.current
    val context = LocalContext.current
    val performHaptic = enabled && hapticsEnabled && userHapticsEnabled
    pointerInput(performHaptic) {
        if (!performHaptic) return@pointerInput
        awaitEachGesture {
            // Fire on finger-down like LightOS
            awaitFirstDown(requireUnconsumed = false)
            LightHapticFeedback.click(context)
        }
    }.clickable(
        interactionSource = null,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    )
}
