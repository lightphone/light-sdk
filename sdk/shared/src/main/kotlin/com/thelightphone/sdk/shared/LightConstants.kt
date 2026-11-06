package com.thelightphone.sdk.shared

object LightConstants {
    const val ACTION_SDK_MARKER = "com.thelightphone.sdk.ACTION_SDK_MARKER"
    const val SDK_VERSION_KEY: String = "com.thelightphone.sdk.SDK_VERSION"

    /** UnifiedPush instance IDs for the two channels */
    const val PUSH_INSTANCE_REMOTE = "light-push"
    const val PUSH_INSTANCE_LOCAL = "light-local"

    /** Message extra sent during registration to signal channel type */
    const val PUSH_CHANNEL_REMOTE = "remote"
    const val PUSH_CHANNEL_LOCAL = "local"

    /** Intent action used to bind to the LightSdkService */
    const val ACTION_BIND_SDK_SERVICE = "com.thelightphone.sdk.action.BIND_SERVICE"

    /** Binder transaction code for client -> server requests */
    const val TRANSACTION_REQUEST = 1

    /**
     * Application meta-data the Light Gradle plugin writes for tools that declare
     * `capabilities = ["alerts"]` in lighttool.toml.
     */
    const val CAPABILITY_ALERTS_MARKER = "com.thelightphone.sdk.CAPABILITY_ALERTS"

    /**
     * Notification extra (the alert id) marking a notification as a scheduled tool alert.
     * The server foregrounds the posting tool when it sees one.
     */
    const val EXTRA_ALERT_ID = "com.thelightphone.sdk.extra.ALERT_ID"
}