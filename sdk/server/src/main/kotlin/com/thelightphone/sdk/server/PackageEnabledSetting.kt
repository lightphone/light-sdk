package com.thelightphone.sdk.server

import android.content.pm.PackageManager

enum class PackageEnabledSetting(val androidValue: Int) {
    Default(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
    Enabled(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
    Disabled(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
    DisabledByUser(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER),
    DisabledUntilUsed(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED);

    internal fun canBeRestricted(): Boolean =
        when (this) {
            Default, Enabled, DisabledUntilUsed -> true
            Disabled, DisabledByUser -> false
        }

    companion object {
        fun fromAndroid(value: Int): PackageEnabledSetting =
            requireNotNull(entries.find { it.androidValue == value }) {
                "Unknown package enabled setting: $value"
            }
    }
}
