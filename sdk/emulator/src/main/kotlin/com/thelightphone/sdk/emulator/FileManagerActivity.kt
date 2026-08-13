package com.thelightphone.sdk.emulator

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.thelightphone.sdk.server.filemanager.FileManagerLifecycleWrapperService
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress

private const val DEFAULT_STATUS = "Connecting..."

class FileManagerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "FileManagerActivity"
    }

    private var fileManagerService: FileManagerLifecycleWrapperService? = null
    private var monitorJob: Job? = null

    private var statusText by mutableStateOf(DEFAULT_STATUS)
    private var fileManagerUrl by mutableStateOf<String?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            fileManagerService = (binder as FileManagerLifecycleWrapperService.LocalBinder).getService()
            lifecycleScope.launch(Dispatchers.IO) {
                showServiceState()
            }

        }

        override fun onServiceDisconnected(name: ComponentName) {
            fileManagerService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        setContent {
            val themeColors by LightThemeController.colors.collectAsState()
            LightTheme(colors = themeColors) {
                FileManagerScreen(
                    statusText = statusText,
                    fileManagerUrl = fileManagerUrl,
                    onBack = {
                        FileManagerLifecycleWrapperService.stop(this)
                        finish()
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        FileManagerLifecycleWrapperService.start(this)
        bindService(
            Intent(this, FileManagerLifecycleWrapperService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        startMonitoring()
    }

    override fun onStop() {
        super.onStop()
        monitorJob?.cancel()
        monitorJob = null
        unbindService(serviceConnection)
        fileManagerService = null
    }

    private fun showServiceState() {
        val service = fileManagerService ?: return
        if (!service.isRunning) {
            statusText = "Failed to start file manager"
            return
        }
        // in the emulator, the server is only reachable via localhost on the host machine
        // (through adb's port forwarding), so this URL is only usable from this machine.
        // don't forget to forward the ports after each emulator restart:
        // adb forward tcp:54448 tcp:54448
        // adb forward tcp:54449 tcp:54449
        val url = service.getHttpsUrl(InetAddress.getByName("localhost"))
        if (url.isNullOrEmpty()) {
            statusText = "Failed to start file manager"
            return
        }
        Log.d(TAG, "File manager running at $url")
        fileManagerUrl = url
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch {
            while (isActive) {
                delay(2000)
                val service = fileManagerService
                if (service != null && !service.isRunning) {
                    statusText = "File manager has stopped running."
                    Log.e(TAG, "File manager terminated")
                    cancel()
                }
            }
        }
    }
}

@Composable
private fun FileManagerScreen(
    statusText: String,
    fileManagerUrl: String?,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("File Manager"),
            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            if (fileManagerUrl != null) {
                SelectionContainer {
                    LightText(
                        text = fileManagerUrl,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                    )
                }
            } else {
                LightText(
                    text = statusText,
                    variant = LightTextVariant.Paragraph,
                    align = TextAlign.Center,
                )
            }
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun FileManagerScreenPreviewLoading() {
    LightTheme(colors = LightThemeColors.Dark) {
        FileManagerScreen(
            statusText = DEFAULT_STATUS,
            fileManagerUrl = null,
            onBack = {},
        )
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun FileManagerScreenPreviewUrl() {
    LightTheme(colors = LightThemeColors.Dark) {
        FileManagerScreen(
            statusText = DEFAULT_STATUS,
            fileManagerUrl = "https://127-0-0-1.my.local-ip.co:54449/#abc123",
            onBack = {},
        )
    }
}
