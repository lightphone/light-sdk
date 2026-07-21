package com.thelightphone.sdk

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.File

private class BackStackEntry<T>(
    val screen: SimpleLightScreen<T>,
    val callback: ((T) -> Unit)? = null,
) {
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
            val screen = currentScreen.value?.screen
            if (screen != null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        val content: @Composable () -> Unit = { screen.Content() }
                        if (screen is ViewModelStoreOwner) {
                            CompositionLocalProvider(
                                LocalViewModelStoreOwner provides screen,
                                content = content,
                            )
                        } else {
                            content()
                        }
                    }
                }
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
