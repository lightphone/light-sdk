package com.thelightphone.sdk.ui

import android.view.KeyEvent

interface LightKeyHandler {
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false

    fun onKeyMultiple(
        keyCode: Int,
        repeatCount: Int,
        event: KeyEvent
    ): Boolean = false
}