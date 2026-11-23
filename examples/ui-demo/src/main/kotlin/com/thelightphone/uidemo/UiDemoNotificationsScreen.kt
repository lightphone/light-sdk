package com.thelightphone.uidemo

import android.Manifest
import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberPermissionRequestLauncher
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
import com.thelightphone.sdk.ui.lightClickable

/**
 * Posts and cancels a plain notification.
 */
class UiDemoNotificationsScreen(sealedActivity: SealedLightActivity) :
    SimpleLightScreen<Unit>(sealedActivity) {
    private var askedForNotifications = false
    private var posted = 0
    private var status by mutableStateOf("")

    @SuppressLint("MissingPermission")
    private fun post() {
        val notifications = lightContext.notifications
        if (!notifications.canPostNotifications()) {
            status = "Notifications are not allowed"
            return
        }
        posted += 1
        notifications.post(title = "UI demo", text = "Notification $posted")
        status = "Posted notification $posted"
    }

    private fun cancel() {
        lightContext.notifications.cancel()
        status = "Notification cancelled"
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val notificationsLauncher = rememberPermissionRequestLauncher(Manifest.permission.POST_NOTIFICATIONS)

        LaunchedEffect(notificationsLauncher) {
            if (notificationsLauncher == null || askedForNotifications) return@LaunchedEffect
            if (lightContext.notifications.canPostNotifications()) return@LaunchedEffect
            askedForNotifications = true
            notificationsLauncher.launch()
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
                        onClick = { goBack(null) },
                    ),
                    center = LightTopBarCenter.Text("Notifications"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "POST",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .lightClickable { post() }
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                    LightText(
                        text = "CANCEL",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .lightClickable { cancel() }
                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                    )
                    if (status.isNotEmpty()) {
                        LightText(
                            text = status,
                            variant = LightTextVariant.Superfine,
                            modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
                        )
                    }
                }
            }
        }
    }
}
