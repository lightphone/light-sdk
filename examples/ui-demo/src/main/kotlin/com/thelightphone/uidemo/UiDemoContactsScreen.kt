package com.thelightphone.uidemo

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightContacts
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
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

class UiDemoContactsScreen(sealedLightActivity: SealedLightActivity) :
    SimpleLightScreen<Unit>(sealedLightActivity) {
    private var resumeCount by mutableIntStateOf(0)

    override fun willShow() {
        resumeCount++
    }

    @Composable
    override fun Content() {
        var contacts by remember { mutableStateOf<List<LightContacts.Contact>?>(null) }
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.READ_CONTACTS)

        LaunchedEffect(resumeCount) {
            val granted = checkPermission(Manifest.permission.READ_CONTACTS).asKotlinResult
                .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
                .getOrDefault(false)
            if (!granted) {
                permissionLauncher?.launch()
                return@LaunchedEffect
            }
            contacts = lightContext.contacts.load().contacts
        }

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
                    center = LightTopBarCenter.Text("Contacts"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    val loaded = contacts
                    when {
                        loaded == null -> LightText(
                            text = "Contacts permission required for this screen",
                            variant = LightTextVariant.Copy,
                        )
                        loaded.isEmpty() -> LightText(
                            text = "No contacts",
                            variant = LightTextVariant.Copy,
                        )
                        else -> loaded.forEach { contact ->
                            LightText(
                                text = contact.name,
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                            )
                        }
                    }
                }
            }
        }
    }
}
