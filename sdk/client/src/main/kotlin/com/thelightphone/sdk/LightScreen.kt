package com.thelightphone.sdk

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import com.thelightphone.sdk.textentry.LightTextEditResult
import com.thelightphone.sdk.textentry.TextEditOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBarItem
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightKeyHandler
import com.thelightphone.sdk.ui.LightTextInputEditor
import kotlinx.coroutines.launch

abstract class SimpleLightScreen<ResultType>(sealedActivity: SealedLightActivity) :
    LightKeyHandler {
    internal val activity = sealedActivity.activity
    internal var result: ResultType? = null
    protected val lightContext = SealedLightContext(sealedActivity.activity)

    @Composable
    abstract fun Content()
    open fun willShow() {}
    open fun willHide() {}
    open fun onAppPause() {}
    open fun onScreenDestroy() {}

    internal open fun notifyWillShow() {
        willShow()
    }

    internal open fun notifyWillHide() {
        willHide()
    }

    internal open fun notifyAppPause() {
        onAppPause()
    }

    internal open fun destroy() {
        onScreenDestroy()
    }

    fun <T> navigateTo(screenFactory: (SealedLightActivity) -> SimpleLightScreen<T>, resultCallback: ((T) -> Unit)? = null) {
        val screen = screenFactory(SealedLightActivity(activity))
        activity.navigateTo(screen, resultCallback)
    }

    /**
     * Lets the user edit [text] full screen, then calls [onResult] with how they finished.
     * if a keyboard app is available, it opens straight away. Otherwise it shows [LightTextInputEditor]
     * on a screen of its own, which closes when the user finishes.
     *
     * The options are [LightTextInputEditor]'s. Hitting a bottom bar item finishes the edit with
     * [LightTextEditResult.BottomBarItem] instead of calling the item's onClick. [onResult] is
     * called on the main thread, even if this screen has left composition in the meantime.
     */
    fun editText(
        title: String,
        text: String,
        singleLine: Boolean = false,
        initialCaps: Boolean = false,
        submitLabel: String = "SUBMIT",
        submitIcon: LightIconConfiguration? = null,
        leftBottomBarItem: LightBottomBarItem? = null,
        rightBottomBarItem: LightBottomBarItem? = null,
        showBackButton: Boolean = true,
        onResult: (LightTextEditResult) -> Unit,
    ) {
        val options = TextEditOptions(
            title, text, singleLine, initialCaps, submitLabel, submitIcon,
            leftBottomBarItem, rightBottomBarItem, showBackButton,
        )
        // the activity's scope, not the screen's composition: the fallback editor screen takes
        // this one out of composition until it closes
        activity.lifecycleScope.launch { onResult(activity.editText(options)) }
    }

    open fun goBack(result: ResultType? = null) {
        this.result = result
        activity.goBack()
    }
}

abstract class LightScreen<ResultType, VM : LightViewModel<ResultType>>(
    sealedActivity: SealedLightActivity
) : SimpleLightScreen<ResultType>(sealedActivity), ViewModelStoreOwner {
    abstract val viewModelClass: Class<VM>
    abstract fun createViewModel(): VM

    override val viewModelStore: ViewModelStore = ViewModelStore()

    @Suppress("UNCHECKED_CAST")
    val viewModel: VM by lazy {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return createViewModel() as T
            }
        }
        ViewModelProvider(this, factory)[viewModelClass]
    }

    override fun destroy() {
        super.destroy()
        viewModelStore.clear()
    }

    override fun notifyWillShow() {
        super.notifyWillShow()
        viewModel.onScreenShow(this)
    }

    override fun notifyWillHide() {
        super.notifyWillHide()
        viewModel.onScreenHide(this)
    }

    override fun notifyAppPause() {
        super.notifyAppPause()
        viewModel.onAppPause()
    }

    override fun goBack(result: ResultType?) {
        if (!viewModel.onBackPressed()) {
            super.goBack(result)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = viewModel.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent)= viewModel.onKeyUp(keyCode, event)

    override fun onKeyMultiple(
        keyCode: Int,
        repeatCount: Int,
        event: KeyEvent
    ) = viewModel.onKeyMultiple(keyCode, repeatCount, event)
}
