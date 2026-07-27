package com.thelightphone.uidemo

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

private const val MAX_EVENTS = 20

class UiDemoKeyEventsScreen(sealedActivity: SealedLightActivity) :
    SimpleLightScreen<Unit>(sealedActivity) {

    // Newest-first, capped. Mutated from key dispatch (main thread), read by Content().
    private val events = mutableStateListOf<String>()

    private fun record(type: String, keyCode: Int, event: KeyEvent) {
        events.add(0, "${KeyEvent.keyCodeToString(keyCode)} ($keyCode) $type " +
            "action=${event.action} repeat=${event.repeatCount}")
        if (events.size > MAX_EVENTS) events.removeRange(MAX_EVENTS, events.size)
    }

    // Return true to consume: keeps this screen self-contained (no server forwarding).
    // BACK/HOME never arrive here — LightActivity short-circuits them before the screen.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        record("DOWN", keyCode, event)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        record("UP", keyCode, event)
        return true
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        record("MULTIPLE", keyCode, event)
        return true
    }

    @Composable
    override fun Content() = ContentInner(events, onBack = { goBack() })
}

@Composable
private fun ContentInner(events: List<String>, onBack: () -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                ),
                center = LightTopBarCenter.Text("Key Events"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            if (events.isEmpty()) {
                LightText(
                    text = "Press a hardware key…",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                )
            }
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                events.forEach { line ->
                    LightText(
                        text = line,
                        variant = LightTextVariant.Superfine,
                        modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun UiDemoKeyEventsScreenPreview() {
    ContentInner(
        events = listOf(
            "DOWN  KEYCODE_VOLUME_UP (24)  action=0 repeat=0",
            "UP  KEYCODE_VOLUME_UP (24)  action=1 repeat=0",
        ),
        onBack = {},
    )
}
