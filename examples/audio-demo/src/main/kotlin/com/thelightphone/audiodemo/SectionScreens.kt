package com.thelightphone.audiodemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.rememberLightAudio
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

class AudioSectionViewModel : LightViewModel<Unit>() {
    lateinit var audio: LightAudio
        private set

    fun attachAudio(value: LightAudio) {
        if (!::audio.isInitialized) audio = value
    }
}

abstract class AudioSectionScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
) : LightScreen<Unit, AudioSectionViewModel>(sealedActivity) {
    override val viewModelClass = AudioSectionViewModel::class.java

    override fun createViewModel() = AudioSectionViewModel()

    @Composable
    override fun Content() {
        viewModel.attachAudio(rememberLightAudio())
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
                        contentDescription = "Back",
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text(title),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = title.uppercase(),
                        variant = LightTextVariant.Copy,
                        align = TextAlign.Center,
                    )
                }
            }
        }
    }
}
