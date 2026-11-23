package com.thelightphone.sdk.server.backup

import com.thelightphone.sdk.digest.digestHex
import com.thelightphone.sdk.digest.newSha256Digest
import java.io.InputStream

internal fun InputStream.digestHexString(): String = use { input ->
    val digest = newSha256Digest()
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digestHex()
}
