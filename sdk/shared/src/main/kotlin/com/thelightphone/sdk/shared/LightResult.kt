package com.thelightphone.sdk.shared

sealed interface LightResult<out T> {
    enum class ErrorCode {
        // DO NOT REORDER/REMOVE — ordinals are sent over the binder wire. Append new codes only.
        Unknown, Removed, InvalidParameters, NoPermission, InvalidToken;
    }
    data class Success<T>(val data: T) : LightResult<T>
    data class Error(val code: ErrorCode, val extra: String? = null) : LightResult<Nothing>
}

fun <T> LightResult<T>.getOrNull() = when(this) {
    is LightResult.Error -> null
    is LightResult.Success<T> -> this.data
}

inline fun <T> LightResult<T>.getOrElse(block: (LightResult.Error) -> T) = when(this) {
    is LightResult.Error -> block(this)
    is LightResult.Success<T> -> this.data
}

val LightResult<*>.error: LightResult.Error?
get() = when(this) {
    is LightResult.Error -> this
    is LightResult.Success<*> -> null
}

val <T> LightResult<T>.asKotlinResult: Result<T>
    get() = when(this) {
        is LightResult.Error -> Result.failure(Exception(this.toString()))
        is LightResult.Success<T> -> Result.success(this.data)
    }
