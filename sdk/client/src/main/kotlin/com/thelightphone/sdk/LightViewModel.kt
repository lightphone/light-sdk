package com.thelightphone.sdk

import androidx.lifecycle.ViewModel
import com.thelightphone.sdk.ui.LightKeyHandler

abstract class LightViewModel<T> : ViewModel(), LightKeyHandler {
    open fun onScreenShow(screen: SimpleLightScreen<T>) {}
    open fun onScreenHide(screen: SimpleLightScreen<T>) {}
    open fun onAppPause() {}

    /**
     * returns false if the ViewModel does not consume the back button event
     */
    open fun onBackPressed(): Boolean = false
}
