package com.thelightphone.uidemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

private const val DEMO_ROW_COUNT = 32

class UiDemoScrollViewModel : LightViewModel<Unit>()

class UiDemoScrollScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, UiDemoScrollViewModel>(sealedActivity) {

    override val viewModelClass: Class<UiDemoScrollViewModel>
        get() = UiDemoScrollViewModel::class.java

    override fun createViewModel() = UiDemoScrollViewModel()

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
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Scrolling"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(
                            start = 1f.gridUnitsAsDp(),
                            bottom = 1f.gridUnitsAsDp(),
                        ),
                ) {
                    repeat(DEMO_ROW_COUNT) { index ->
                        LightText(
                            text = "List item ${index + 1}",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                        )
                    }
                }
            }
        }
    }
}
