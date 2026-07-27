package com.thelightphone.sdk

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.thelightphone.lp3Keyboard.ui.LightDeviceKeys
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightModalManager
import kotlinx.coroutines.launch
import java.io.File

private class ScreenViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

private class BackStackEntry<T>(
    val screen: SimpleLightScreen<T>,
    val callback: ((T) -> Unit)? = null,
) {
    // SimpleLightScreens that aren't already a ViewModelStoreOwner (LightScreen) still need a
    // store scoped to this specific navigation instance, so composables inside them that call
    // viewModel() don't resolve against the long-lived Activity store and get a stale ViewModel
    // handed back on a later, unrelated navigation to a screen with the same viewModel() key.
    val viewModelStoreOwner: ViewModelStoreOwner =
        screen as? ViewModelStoreOwner ?: ScreenViewModelStoreOwner()

    fun deliverResult() {
        val result = screen.result ?: return
        callback?.invoke(result)
    }
}

class LightActivity internal constructor() : ComponentActivity() {

    private val backStack = mutableListOf<BackStackEntry<*>>()
    private val currentScreen = mutableStateOf<BackStackEntry<*>?>(null)
    private var contentReady = false
    private val createdAt = android.os.SystemClock.elapsedRealtime()

    internal fun <T> navigateTo(screen: SimpleLightScreen<T>, resultCallback: ((T) -> Unit)? = null) {
        currentScreen.value?.screen?.notifyWillHide()
        val entry = BackStackEntry(screen, resultCallback)
        backStack.add(entry)
        screen.notifyWillShow()
        currentScreen.value = entry
    }

    internal fun goBack() {
        val current = currentScreen.value ?: return
        val popped = current.screen
        popped.notifyWillHide()
        popped.destroy()
        current.viewModelStoreOwner.viewModelStore.clear()
        backStack.removeAt(backStack.lastIndex)
        if (backStack.isEmpty()) {
            finish()
            return
        }
        val previous = backStack.last()
        previous.screen.notifyWillShow()
        currentScreen.value = previous
        current.deliverResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition {
            !contentReady || android.os.SystemClock.elapsedRealtime() - createdAt < 1000
        }
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        val factory = LightSdkRegistry.initialScreenFactory
            ?: throw IllegalStateException("No class annotated with @InitialScreen found")

        val initial = BackStackEntry(factory(SealedLightActivity(this)))

        backStack.add(initial)
        currentScreen.value = initial

        setContent {
            androidx.compose.runtime.LaunchedEffect(Unit) { contentReady = true }
            Box(modifier = Modifier.fillMaxSize()) {
                val entry = currentScreen.value
                if (entry != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            CompositionLocalProvider(
                                LocalViewModelStoreOwner provides entry.viewModelStoreOwner,
                                content = { entry.screen.Content() },
                            )
                        }
                    }
                }
                // Transient modals draw on top of the current screen.
                val activeModal by LightModalManager.activeModal.collectAsState()
                activeModal?.Content()
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    goBack()
                }
            }
        )
    }

    private val Int.isSystemKeyCode: Boolean
        get() = (this == KeyEvent.KEYCODE_BACK || this == KeyEvent.KEYCODE_HOME)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // don't do anything with android keys
        // back button override handled elsewhere and home button won't get dispatched to external tools
        return if (keyCode.isSystemKeyCode) {
            super.onKeyDown(keyCode, event)
        } else if (currentScreen.value?.screen?.onKeyDown(keyCode, event) == true) {
            // let the active screen handle the button if it wants to
            true
        } else if (LightDeviceKeys.mapping.containsKey(keyCode)) {
            // otherwise see if the server wants to use it
            forwardKeyEventToServer(keyCode, event)
            true
        } else {
            false
        }
    }

    override fun onKeyMultiple(
        keyCode: Int,
        repeatCount: Int,
        event: KeyEvent
    ): Boolean {
        return if (keyCode.isSystemKeyCode) {
            super.onKeyMultiple(keyCode, repeatCount, event)
        } else if (currentScreen.value?.screen?.onKeyMultiple(keyCode, repeatCount, event) == true) {
            true
        } else if (LightDeviceKeys.mapping.containsKey(keyCode)) {
            forwardKeyEventToServer(keyCode, event)
            true
        } else {
            false
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyCode.isSystemKeyCode) {
            super.onKeyUp(keyCode, event)
        } else if (currentScreen.value?.screen?.onKeyUp(keyCode, event) == true) {
            true
        } else if (LightDeviceKeys.mapping.containsKey(keyCode)) {
            forwardKeyEventToServer(keyCode, event)
            true
        } else {
            false
        }
    }

    private fun forwardKeyEventToServer(
        keyCode: Int,
        event: KeyEvent,
    ) {
        lifecycleScope.launch {
            callRemoteServiceMethod(
                LightServiceMethod.DeviceKeyEvent,
                LightServiceMethod.DeviceKeyEvent.Request(
                    keyCode = keyCode,
                    repeatCount = event.repeatCount,
                    action = event.action,
                    characters = event.characters,
                    unicodeChar = event.unicodeChar,
                    componentToRelaunch = componentName.flattenToString()
                )
            )
        }
    }

    override fun onPause() {
        super.onPause()
        currentScreen.value?.screen?.notifyAppPause()
    }

    override fun onResume() {
        super.onResume()
        currentScreen.value?.screen?.notifyWillShow()
    }
}

class SealedLightContext(internal val androidContext: Context) {
    val dataStore: DataStore<Preferences> by lazy{ androidContext.dataStore }
    val filesDir: File by lazy{ androidContext.filesDir }
    val fileShare: LightFileShare by lazy { LightFileShare(androidContext) }
    fun readAsset(path: String): ByteArray = androidContext.assets.open(path).use { it.readBytes() }
}
/**
 * Wrapper class to pass around an instance of LightActivity without exposing it to
 * user code. Sorry! :)
 */
class SealedLightActivity(internal val activity: LightActivity)

internal val Context.dataStore by preferencesDataStore(
    name = "DEFAULT_DATASTORE"
)
