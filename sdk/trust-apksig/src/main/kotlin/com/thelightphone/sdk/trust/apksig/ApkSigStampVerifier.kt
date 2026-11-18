package com.thelightphone.sdk.trust.apksig

import com.thelightphone.sdk.trust.StampVerifier
import com.thelightphone.sdk.trust.StampResult

import com.android.apksig.SourceStampVerifier
import java.io.File
import java.security.MessageDigest

class ApkSigStampVerifier : StampVerifier {
    override fun verify(apkPaths: List<String>): StampResult {
        if (apkPaths.size != 1) return StampResult.Unavailable("split APK verification is platform-only")
        return try {
            val result = SourceStampVerifier.Builder(File(apkPaths.single())).build().verifySourceStamp()
            val info = result.sourceStampInfo
            when {
                info == null -> StampResult.NotPresent
                !result.isVerified -> StampResult.NotVerified
                else -> StampResult.Verified(
                    certSha256 = sha256(info.certificate.encoded),
                    lineageSha256 = info.certificatesInLineage.map { sha256(it.encoded) },
                )
            }
        } catch (error: Exception) {
            StampResult.Unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }
}
