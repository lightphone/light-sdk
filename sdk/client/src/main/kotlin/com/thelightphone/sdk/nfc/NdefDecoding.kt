package com.thelightphone.sdk.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import java.io.IOException

internal fun Tag.readTap(): LightNfcTap =
    LightNfcTap(serialNumber = id.toHexSerial(), records = readNdefRecords())

internal fun Tag.readNdefRecords(): List<LightNfcRecord> {
    val ndef = Ndef.get(this) ?: return emptyList()
    val message = ndef.cachedNdefMessage ?: ndef.readConnected() ?: return emptyList()
    return message.records.map { it.toLightNfcRecord() }
}

private fun Ndef.readConnected(): NdefMessage? = try {
    connect()
    ndefMessage
} catch (e: IOException) {
    throw LightNfcReadException(READ_FAILED_MESSAGE, e)
} catch (e: FormatException) {
    throw LightNfcReadException(UNREADABLE_MESSAGE, e)
} finally {
    runCatching { close() }
}

internal fun NdefRecord.toLightNfcRecord(): LightNfcRecord = decodeNdefRecord(
    tnf = tnf,
    type = type,
    payload = payload,
    platformUri = { runCatching { toUri() }.getOrNull()?.toString() },
    platformMimeType = { runCatching { toMimeType() }.getOrNull() },
)

internal fun decodeNdefRecord(
    tnf: Short,
    type: ByteArray,
    payload: ByteArray,
    platformUri: () -> String?,
    platformMimeType: () -> String?,
): LightNfcRecord {
    if (tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(RTD_TEXT_TYPE)) {
        decodeTextRecord(payload)?.let { return it }
    }
    if (carriesUri(tnf, type)) {
        platformUri()?.let { return LightNfcRecord.Uri(it) }
    }
    return LightNfcRecord.Binary(mimeType = platformMimeType(), bytes = payload)
}

private fun carriesUri(tnf: Short, type: ByteArray): Boolean = when (tnf) {
    NdefRecord.TNF_ABSOLUTE_URI -> true
    NdefRecord.TNF_WELL_KNOWN ->
        type.contentEquals(RTD_URI_TYPE) || type.contentEquals(RTD_SMART_POSTER_TYPE)

    else -> false
}

internal fun decodeTextRecord(payload: ByteArray): LightNfcRecord.Text? {
    if (payload.isEmpty()) return null
    val status = payload[0].toInt()
    val languageLength = status and TEXT_LANGUAGE_LENGTH_MASK
    if (payload.size < 1 + languageLength) return null
    val charset = if (status and TEXT_UTF16_FLAG != 0) Charsets.UTF_16 else Charsets.UTF_8
    return LightNfcRecord.Text(
        value = String(payload, 1 + languageLength, payload.size - 1 - languageLength, charset),
        languageTag = String(payload, 1, languageLength, Charsets.US_ASCII),
    )
}

internal fun ByteArray.toHexSerial(): String = buildString(size * 2) {
    for (byte in this@toHexSerial) {
        val value = byte.toInt() and BYTE_MASK
        append(HEX_DIGITS[value shr NIBBLE_BITS])
        append(HEX_DIGITS[value and NIBBLE_MASK])
    }
}

private val RTD_TEXT_TYPE = "T".toByteArray(Charsets.US_ASCII)
private val RTD_URI_TYPE = "U".toByteArray(Charsets.US_ASCII)
private val RTD_SMART_POSTER_TYPE = "Sp".toByteArray(Charsets.US_ASCII)
private const val TEXT_LANGUAGE_LENGTH_MASK = 0x3F
private const val TEXT_UTF16_FLAG = 0x80
private const val BYTE_MASK = 0xFF
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F
private const val HEX_DIGITS = "0123456789ABCDEF"
