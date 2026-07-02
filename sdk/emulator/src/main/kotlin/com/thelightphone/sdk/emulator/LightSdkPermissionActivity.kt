package com.thelightphone.sdk.emulator

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.server.LightSdkServerSettings
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val TAG = "LightSdkPermissionActivity"

// TODO - eventually we should bring this down to the server module and make it the default for servers
class LightSdkPermissionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        fun exit(message: String) {
            Log.e(TAG, message)
            finish()
        }

        val requester = callingPackage ?: return exit("Calling package was null, exiting")
        val requesterUid = packageManager.getPackageUid(
            requester,
            PackageManager.PackageInfoFlags.of(0)
        )
        val permission = intent.getStringExtra(LightServiceMethod.RequestPermissionComponent.PERMISSION_NAME_KEY)
            ?: return exit("Missing permission extra, exiting")

        val permissionAllowed = LightSdkServer.androidPermissionAllowed(requesterUid, permission)
        if (!permissionAllowed) {
            Log.w(TAG, "requested permission: $permission is not grantable by this server")
        }

        val toolName = packageManager
            .getApplicationInfo(requester, 0)
            .loadLabel(packageManager)
            .toString()

        setContent {
            var loading by remember { mutableStateOf(true) }
            var packageAllowed by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    val clientFilterLevel = LightSdkServerSettings(this@LightSdkPermissionActivity).clientFilterLevel
                    packageAllowed = permissionAllowed && LightSdkServer.isPackageAllowed(clientFilterLevel, requester)
                    loading = false
                }
            }
            val themeColors by LightThemeController.colors.collectAsState()
            LightTheme(colors = themeColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!permissionAllowed) {
                        LightText("Not allowed", LightTextVariant.Heading)
                        // TODO don't let them request it
                    } else if (loading) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        }
                    } else if (packageAllowed) {
                        RequestPermission(toolName, permission, onDenied = { finish() }) {
                            LightSdkServer.grantPermission(this, requester, permission)
                                .onFailure { Log.e(TAG, "Error granting permission", it) }
                            finish()
                        }
                    } else {
                        LightText("Tool blocked", LightTextVariant.Heading)
                        // TODO Let user know an unacceptable app is requesting permission
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestPermission(
    toolName: String,
    permission: String,
    onDenied: () -> Unit,
    onGranted: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LightText("The tool:", LightTextVariant.Paragraph)
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                LightText(toolName, LightTextVariant.Heading)
                Spacer(Modifier.height(1.5f.gridUnitsAsDp()))
                LightText("is requesting permission to use:", LightTextVariant.Paragraph)
                Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                LightText(permission, LightTextVariant.Subheading)
            }
        }
        LightBottomBar(
            listOf(
                LightBarButton.Text("DENY") { onDenied() },
                LightBarButton.Text("ACCEPT") { onGranted() },
            )
        )
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun RequestPermissionPreview() {
    LightTheme(colors = LightThemeColors.Dark) {
        Surface {
            RequestPermission("Example Tool", "com.android.CAMERA", onDenied = {}) { }
        }
    }
}
