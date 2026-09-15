package com.thelightphone.sdk.trust

fun interface StampVerifier {
    fun verify(apkPaths: List<String>): StampResult
}

sealed interface StampResult {
    data class Verified(
        val certSha256: String,
        val lineageSha256: List<String>,
    ) : StampResult

    data object NotPresent : StampResult
    data object NotVerified : StampResult
    data class Unavailable(val cause: String) : StampResult
}
