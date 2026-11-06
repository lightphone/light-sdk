package com.thelightphone.uidemo

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightAlert
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.LightNotifications
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The most recent alert delivered to [UiDemoEntryPoint], until the user closes it. */
object UiDemoAlerts {
    private val _received = MutableStateFlow<LightAlert?>(null)
    val received: StateFlow<LightAlert?> = _received.asStateFlow()

    fun receive(alert: LightAlert) {
        _received.value = alert
    }

    fun clear() {
        _received.value = null
    }
}

@EntryPoint
object UiDemoEntryPoint : LightEntryPoint {
    override suspend fun onAlert(alert: LightAlert): Boolean {
        UiDemoAlerts.receive(alert)
        // Drop the alert now that it has been shown, so a reboot doesn't deliver it again.
        return true
    }
}

/** Shows the received alert over any screen; closing it lets the screen sleep again. */
@Composable
fun UiDemoAlertModal(notifications: LightNotifications) {
    val alert by UiDemoAlerts.received.collectAsState()
    alert?.let {
        LightFullscreenModal(
            message = listOf(it.title, it.text).filter(String::isNotBlank).joinToString("\n"),
            onClose = {
                notifications.releaseScreen()
                UiDemoAlerts.clear()
            },
        )
    }
}

class UiDemoAlertsViewModel : LightViewModel<Unit>() {
    private val _title = MutableStateFlow("Demo alert")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _text = MutableStateFlow("Scheduled from the UI demo")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _seconds = MutableStateFlow("10")
    val seconds: StateFlow<String> = _seconds.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    fun setTitle(value: String) {
        _title.value = value
    }

    fun setText(value: String) {
        _text.value = value
    }

    fun setSeconds(value: String) {
        _seconds.value = value.trim()
    }

    fun schedule(notifications: LightNotifications) {
        val seconds = _seconds.value.toLongOrNull()
        if (seconds == null || seconds < 0) {
            _status.value = "Seconds must be a whole number"
            return
        }
        _status.value = try {
            notifications.schedule(
                id = ALERT_ID,
                triggerAtMillis = System.currentTimeMillis() + seconds * 1000,
                title = _title.value,
                text = _text.value,
            )
            "Alert in $seconds seconds"
        } catch (error: IllegalStateException) {
            error.message ?: "Could not schedule the alert"
        }
    }

    fun cancel(notifications: LightNotifications) {
        notifications.cancel(ALERT_ID)
        _status.value = "Alert cancelled"
    }

    companion object {
        // One id, so scheduling again replaces the pending demo alert.
        const val ALERT_ID = "ui-demo"
    }
}

class UiDemoAlertsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, UiDemoAlertsViewModel>(sealedActivity) {
    // Asked once per visit, so a denial doesn't reopen the prompt on every resume.
    private var askedForNotifications = false

    override val viewModelClass: Class<UiDemoAlertsViewModel>
        get() = UiDemoAlertsViewModel::class.java

    override fun createViewModel() = UiDemoAlertsViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val title by viewModel.title.collectAsState()
        val text by viewModel.text.collectAsState()
        val seconds by viewModel.seconds.collectAsState()
        val status by viewModel.status.collectAsState()
        val notifications = lightContext.notifications
        val scheduledIds by notifications.scheduledAlertIds.collectAsState()
        val scheduled = UiDemoAlertsViewModel.ALERT_ID in scheduledIds
        val notificationsLauncher = rememberPermissionRequestLauncher(Manifest.permission.POST_NOTIFICATIONS)

        LaunchedEffect(notificationsLauncher) {
            if (notificationsLauncher == null || askedForNotifications) return@LaunchedEffect
            if (notifications.canPostNotifications()) return@LaunchedEffect
            askedForNotifications = true
            notificationsLauncher.launch()
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack(null) },
                        ),
                        center = LightTopBarCenter.Text("Alerts"),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        AlertField("Title:", title, "Alert title") { viewModel.setTitle(it) }
                        AlertField("Text:", text, "Alert text") { viewModel.setText(it) }
                        AlertField("Seconds:", seconds, "Seconds from now") { viewModel.setSeconds(it) }

                        LightText(
                            text = if (scheduled) "CANCEL" else "SCHEDULE",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .lightClickable {
                                    if (scheduled) viewModel.cancel(notifications)
                                    else viewModel.schedule(notifications)
                                }
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

                UiDemoAlertModal(notifications)
            }
        }
    }

    @Composable
    private fun AlertField(label: String, value: String, placeholder: String, onResult: (String) -> Unit) {
        LightTextField(
            label = label,
            value = value,
            placeholder = placeholder,
            onClick = {
                val editorRequest = EditorRequest(
                    title = label.removeSuffix(":"),
                    initialValue = value,
                )
                navigateTo(
                    screenFactory = { UiDemoTextInputEditorScreen(it, editorRequest) },
                    resultCallback = onResult,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 1f.gridUnitsAsDp()),
        )
    }
}
