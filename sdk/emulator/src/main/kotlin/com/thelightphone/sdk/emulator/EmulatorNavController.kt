package com.thelightphone.sdk.emulator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Nav {
    data object LockScreen : Nav
    data object Toolbox : Nav
    class Settings(
        val startingScreen: EmulatorSettingsNav = EmulatorSettingsNav.Root,
        val backButtonOverride: (() -> Unit)? = null
    ) : Nav
}

object EmulatorNavController {
    private val _currentNav = MutableStateFlow<Nav>(Nav.LockScreen)
    val currentNav = _currentNav.asStateFlow()

    fun navigateTo(nav: Nav) {
        _currentNav.value = nav
    }
}
