package com.thelightphone.sdk.digest

import java.security.MessageDigest

fun ByteArray.sha256Hex(): String =
    newSha256Digest().digest(this).toHexString()

fun newSha256Digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

fun MessageDigest.digestHex(): String = digest().toHexString()
