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
        // judgment: apksig itself doesn't throw for this (it reports sourceStampInfo
        // == null, indistinguishable from "read it, no stamp"), so the distinction
        // has to be made before calling into it.
        if (!file.isFile) return StampResult.Unavailable("APK is not a readable file")
        return try {
            // Presence is checked narrowly (verifySourceStamp(), stamp-against-signing-block
            // only) so a stamp that structurally exists is still recognized even when the
            // APK's main content digest is broken. Whether it can be trusted is then decided
            // by the full-chain verify(): verifySourceStamp() alone verified the stamp against
            // the APK's signature blocks but never those signatures against the APK's bytes,
            // so a stamp-intact content edit used to verify. verify() runs the same v1-v4
            // content-digest checks the platform does, over both the APK and the stamp.
            val verifier = ApkVerifier.Builder(file).build()
            val info = verifier.verifySourceStamp().sourceStampInfo
            when {
                info == null -> StampResult.NotPresent
                else -> {
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
            // Not a valid zip/APK at all: a stable content judgment (re-reading the
            // same bytes always gives the same answer), not an infrastructure failure.
            StampResult.NotPresent
        } catch (error: Exception) {
            StampResult.Unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }
}
