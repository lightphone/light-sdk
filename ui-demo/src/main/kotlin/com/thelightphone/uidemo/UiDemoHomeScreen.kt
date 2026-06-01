package com.thelightphone.uidemo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

class UiDemoHomeViewModel : LightViewModel()

@InitialScreen
class UiDemoHomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<UiDemoHomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<UiDemoHomeViewModel>
        get() = UiDemoHomeViewModel::class.java

    override val showBackBar: Boolean = false

    override fun createViewModel() = UiDemoHomeViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
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
                }
            }
        }
    }
}
