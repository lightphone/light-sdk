package com.thelightphone.sdk.shared

sealed interface LightResult<out T> {
    enum class ErrorCode {
        // DO NOT REORDER/REMOVE
        Unknown, Removed, InvalidParameters, NoPermission;
    }
    data class Success<T>(val data: T) : LightResult<T>
    data class Error(val code: ErrorCode, val extra: String? = null) : LightResult<Nothing>
}

fun <T> LightResult<T>.getOrNull() = when(this) {
    is LightResult.Error -> null
    is LightResult.Success<T> -> this.data
}

val LightResult<*>.error: LightResult.Error?
get() = when(this) {
    is LightResult.Error -> this
    is LightResult.Success<*> -> null
}
