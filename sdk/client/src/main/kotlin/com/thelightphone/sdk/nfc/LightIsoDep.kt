package com.thelightphone.sdk.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class LightIsoDep internal constructor(
    private val isoDep: IsoDep,
) {
    val maxTransceiveLength: Int
        get() = isoDep.maxTransceiveLength

    var timeoutMs: Int
        get() = isoDep.timeout
        set(value) {
            isoDep.timeout = value
        }

    suspend fun transceive(command: ByteArray): ByteArray {
        require(command.size <= maxTransceiveLength) {
            "command is ${command.size} bytes; this card accepts at most $maxTransceiveLength"
        }
        return withContext(Dispatchers.IO) {
            mapIsoDepFailures { isoDep.transceive(command) }
        }
    }
}

internal suspend fun <T> Tag.useIsoDep(block: suspend (LightIsoDep) -> T): T {
    val isoDep = IsoDep.get(this) ?: throw LightNfcReadException(NOT_A_SMART_CARD_MESSAGE)
    try {
        withContext(Dispatchers.IO) { mapIsoDepFailures { isoDep.connect() } }
        return block(LightIsoDep(isoDep))
    } finally {
        runCatching { isoDep.close() }
    }
}

// A stale tag handle surfaces as SecurityException rather than TagLostException.
internal inline fun <T> mapIsoDepFailures(block: () -> T): T = try {
    block()
} catch (e: TagLostException) {
    throw LightNfcReadException(CARD_LOST_MESSAGE, e)
} catch (e: SecurityException) {
    throw LightNfcReadException(CARD_LOST_MESSAGE, e)
} catch (e: IOException) {
    throw LightNfcReadException(READ_FAILED_MESSAGE, e)
}
