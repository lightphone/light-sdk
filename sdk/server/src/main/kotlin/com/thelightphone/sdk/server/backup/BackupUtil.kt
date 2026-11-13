package com.thelightphone.sdk.server.backup

import java.io.InputStream
import java.security.MessageDigest

internal fun InputStream.digestHexString(): String = use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().toHexString()
}
