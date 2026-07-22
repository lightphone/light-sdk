package com.thelightphone.sample

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightFileShare
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.error
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeScreenViewModel(
    private val fileShare: LightFileShare
) : LightViewModel<Unit>() {

    val ringtones = MutableStateFlow<List<String>>(emptyList())
    val status = MutableStateFlow<String?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        ringtones.value = fileShare.list("ringtones")
    }

    fun selectRingtone(filename: String) {
        val uri = fileShare.getUri("ringtones/$filename").toString()
        viewModelScope.launch {
            status.value = "Setting ringtone..."
            val result = callRemoteServiceMethod(
                LightServiceMethod.SetRingtone,
                LightServiceMethod.SetRingtone.Request(type = 1, uri = uri)
            )
            status.value = result.error?.let {
                Log.e("HomeScreen", "Unable to set ringtone, error code: ${it.code}")
                "Unable to set ringtone!"
            } ?: "Ringtone set: $filename"
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel {
        return HomeScreenViewModel(lightContext.fileShare)
    }

    @Composable
    override fun Content() {
        val ringtones by viewModel.ringtones.collectAsState()
        val status by viewModel.status.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(32.dp)
            ) {
                LightText(
                    text = "Ringtones",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                Row(modifier = Modifier.padding(bottom = 24.dp)) {
                    LightIcon(
                        icon = LightIcons.SETTINGS,
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .lightClickable { LightThemeController.toggle() },
                    )
                    LightIcon(icon = LightIcons.CALL, modifier = Modifier.padding(end = 16.dp))
                    LightIcon(icon = LightIcons.SEARCH, modifier = Modifier.padding(end = 16.dp))
                    LightIcon(icon = LightIcons.TOGGLE_STATE_ON)
                }

                status?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }

                if (ringtones.isEmpty()) {
                    LightText(
                        text = "No ringtones found.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )
                } else {
                    LazyColumn {
                        items(ringtones) { filename ->
                            LightText(
                                text = filename,
                                variant = LightTextVariant.Copy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable { viewModel.selectRingtone(filename) }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
