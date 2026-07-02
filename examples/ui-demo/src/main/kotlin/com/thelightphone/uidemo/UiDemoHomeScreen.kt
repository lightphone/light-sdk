package com.thelightphone.uidemo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

@InitialScreen
class UiDemoHomeScreen(sealedActivity: SealedLightActivity) :
    SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var showModal by rememberSaveable { mutableStateOf(false) }
        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        center = LightTopBarCenter.Text("UI Demo"),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        LightText(
                            text = "OPEN COUNTER",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .clickable { navigateTo(::UiDemoSecondScreen) }
                                .padding(vertical = 0.75f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "SCROLLING DEMO",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .clickable { navigateTo(::UiDemoScrollScreen) }
                                .padding(vertical = 0.75f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "TEXT INPUT",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .clickable { navigateTo(::UiDemoTextInputScreen) }
                                .padding(vertical = 0.75f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "QR CODE SCANNER",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .clickable {
                                    navigateTo(
                                        screenFactory = ::UiDemoQrScannerScreen,
                                        resultCallback = { scannedResult ->
                                            navigateTo(screenFactory = {
                                                UiDemoQrResultScreen(
                                                    it,
                                                    scannedResult
                                                )
                                            })
                                        }
                                    )
                                }
                                .padding(vertical = 0.75f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "MODAL DEMO",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .clickable { showModal = true }
                                .padding(vertical = 0.75f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "ICONS",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .clickable { navigateTo(::UiDemoIconsScreen) }
                                .padding(vertical = 0.75f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "REVERSE THEME",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .clickable { LightThemeController.toggle() }
                                .padding(vertical = 0.75f.gridUnitsAsDp()),
                        )
                    }
                }

                if (showModal) {
                    LightFullscreenModal(
                        message = "Example full-screen modal",
                        onClose = { showModal = false },
                    )
                }
            }
        }
    }
}
