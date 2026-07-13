package com.thelightphone.uidemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

private val GALLERY_EXCLUDED_ICONS = setOf("SPACER")
private const val ICON_GALLERY_ROW_HEIGHT_GRID = 3.5f

class UiDemoIconsViewModel : LightViewModel<Unit>()

class UiDemoIconsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, UiDemoIconsViewModel>(sealedActivity) {

    override val viewModelClass: Class<UiDemoIconsViewModel>
        get() = UiDemoIconsViewModel::class.java

    override fun createViewModel() = UiDemoIconsViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val icons = remember {
            LightIcons.allEntries.filter { (id, _) -> id !in GALLERY_EXCLUDED_ICONS }
        }

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
                    center = LightTopBarCenter.Text("Icons"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightLazyScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(
                            start = 1f.gridUnitsAsDp(),
                            bottom = 1f.gridUnitsAsDp(),
                        ),
                    uniformItemHeightGridUnits = ICON_GALLERY_ROW_HEIGHT_GRID,
                ) {
                    items(icons, key = { it.first }) { (id, icon) ->
                        IconGalleryRow(id = id, icon = icon)
                    }
                }
            }
        }
    }
}

@Composable
private fun IconGalleryRow(
    id: String,
    icon: LightIconConfiguration,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ICON_GALLERY_ROW_HEIGHT_GRID.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = icon,
            size = 2f,
            modifier = Modifier.padding(end = 1.5f.gridUnitsAsDp()),
        )
        LightText(
            text = id,
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
