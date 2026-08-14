package com.thelightphone.sdk.nfc

open class LightNfcException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class LightNfcUnavailableException(message: String, cause: Throwable? = null) :
    LightNfcException(message, cause)

class LightNfcReadException(message: String, cause: Throwable? = null) :
    LightNfcException(message, cause)

internal fun LightNfcAvailability.unavailableMessage(): String = when (this) {
    LightNfcAvailability.Disabled -> DISABLED_MESSAGE
    LightNfcAvailability.PermissionMissing -> PERMISSION_MISSING_MESSAGE
    else -> UNSUPPORTED_MESSAGE
}

internal const val UNSUPPORTED_MESSAGE = "This phone can't use NFC."
internal const val PERMISSION_MISSING_MESSAGE = "This tool doesn't have access to NFC."
internal const val ENABLE_FAILED_MESSAGE = "NFC couldn't start. Try again."
internal const val DISABLED_MESSAGE = "Turn on NFC in Settings, then try again."
internal const val READ_FAILED_MESSAGE = "The tap didn't complete. Try again."
internal const val UNREADABLE_MESSAGE = "That tag's contents couldn't be read."
