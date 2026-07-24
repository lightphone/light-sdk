package com.thelightphone.uidemo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.util.Locale

class UiDemoProgressBarScreen(sealedActivity: SealedLightActivity) :
    SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() = ContentInner(onBack = { goBack() })
}

@Composable
private fun ContentInner(onBack: () -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    var progress by remember { mutableStateOf(0.5f) }
    LightTheme(colors = themeColors) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                ),
                center = LightTopBarCenter.Text("Progress Bar"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            Column(
                horizontalAlignment = CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2f.gridUnitsAsDp())
            ) {
                LightText("%.2f".format(Locale.ROOT, progress), variant = LightTextVariant.Copy)
                LightTouchableProgressBar(themeColors, progress, onValueChange = { progress = it })
            }
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun UiDemoProgressBarScreen() {
    ContentInner(onBack = {})
}
