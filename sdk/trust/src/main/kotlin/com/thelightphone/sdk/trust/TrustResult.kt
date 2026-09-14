package com.thelightphone.sdk.trust

sealed interface TrustResult<out T> {
    data class Success<T>(val value: T) : TrustResult<T>
    data class Failure(val reason: TrustFailure) : TrustResult<Nothing>
}

sealed interface TrustFailure {
    data class InvalidJson(val detail: String) : TrustFailure
    data class DuplicateKey(val key: String) : TrustFailure
    data class InvalidField(val path: String) : TrustFailure
    data class UnsupportedSchema(val version: String) : TrustFailure
    data object InvalidKey : TrustFailure
    data object InvalidSignature : TrustFailure
    data class VersionNotNewer(val incoming: Long, val floor: Long) : TrustFailure
    data object PersistenceFailed : TrustFailure
}

internal class Rejected(val reason: TrustFailure) : RuntimeException()
internal fun reject(reason: TrustFailure): Nothing = throw Rejected(reason)
internal inline fun <T> parsed(block: () -> T): TrustResult<T> = try {
    TrustResult.Success(block())
} catch (error: Rejected) {
    TrustResult.Failure(error.reason)
}
