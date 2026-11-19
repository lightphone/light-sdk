package com.thelightphone.sdk.trust.apksig

import com.thelightphone.sdk.trust.StampVerifier
import com.thelightphone.sdk.trust.StampResult

import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkFormatException
import java.io.File
import java.security.MessageDigest

class ApkSigStampVerifier : StampVerifier {
    override fun verify(apkPaths: List<String>): StampResult {
        if (apkPaths.size != 1) return StampResult.Unavailable("split APK verification is platform-only")
        val file = File(apkPaths.single())
        // A missing/unreadable artifact is an infrastructure failure, not a content
        // apksig doesn't throw for this, but we need to distinguish between unreadable and missing stamp
        if (!file.isFile) return StampResult.Unavailable("APK is not a readable file")
        return try {
            val verifier = ApkVerifier.Builder(file).build()
            val info = verifier.verifySourceStamp().sourceStampInfo
            when {
                info == null -> StampResult.NotPresent
                else -> {
                    // verifySourceStamp() checks the stamp against the signing block,
                    // not the full apk v1-v4 verification, so we still need to run verify()
                    val result = verifier.verify()
                    if (result.isVerified && result.isSourceStampVerified) {
                        StampResult.Verified(
                            certSha256 = sha256(info.certificate.encoded),
                            lineageSha256 = info.certificatesInLineage.map { sha256(it.encoded) },
                        )
                    } else {
                        StampResult.NotVerified
                    }
                }
            }
        } catch (error: ApkFormatException) {
            // artifict not a valid zip/APK (which is not an infrastructure failure)
            StampResult.NotPresent
        } catch (error: Exception) {
            StampResult.Unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }
}
