package com.thelightphone.sdk

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.thelightphone.sdk.ui.LightKeyHandler

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
