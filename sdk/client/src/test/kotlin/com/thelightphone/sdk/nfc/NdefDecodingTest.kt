package com.thelightphone.sdk.nfc

import android.nfc.NdefRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val UTF16_STATUS_BYTE = 0x80

private fun textPayload(languageTag: String, value: String, utf16: Boolean = false): ByteArray {
    val language = languageTag.toByteArray(Charsets.US_ASCII)
    val status = language.size or if (utf16) UTF16_STATUS_BYTE else 0
    val charset = if (utf16) Charsets.UTF_16 else Charsets.UTF_8
    return byteArrayOf(status.toByte()) + language + value.toByteArray(charset)
}

class NdefDecodingTest {
    @Test
    fun externalRecordDecodesAsBinaryNotUri() {
        val payload = "com.thelightphone.wallet".toByteArray(Charsets.US_ASCII)

        val record = decodeNdefRecord(
            tnf = NdefRecord.TNF_EXTERNAL_TYPE,
            type = "android.com:pkg".toByteArray(Charsets.US_ASCII),
            payload = payload,
            platformUri = { "vnd.android.nfc://ext/android.com:pkg" },
            platformMimeType = { null },
        )

        val binary = assertIs<LightNfcRecord.Binary>(record)
        assertContentEquals(payload, binary.bytes)
    }

    @Test
    fun applicationRecordDoesNotShadowTheTextRecordBesideIt() {
        val address = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        val tap = LightNfcTap(
            serialNumber = "04A2B30F",
            records = listOf(
                decodeNdefRecord(
                    tnf = NdefRecord.TNF_WELL_KNOWN,
                    type = "T".toByteArray(Charsets.US_ASCII),
                    payload = textPayload(languageTag = "en", value = address),
                    platformUri = { null },
                    platformMimeType = { "text/plain" },
                ),
                decodeNdefRecord(
                    tnf = NdefRecord.TNF_EXTERNAL_TYPE,
                    type = "android.com:pkg".toByteArray(Charsets.US_ASCII),
                    payload = "com.thelightphone.wallet".toByteArray(Charsets.US_ASCII),
                    platformUri = { "vnd.android.nfc://ext/android.com:pkg" },
                    platformMimeType = { null },
                ),
            ),
        )

        assertEquals(address, tap.text)
        assertNull(tap.uri)
    }

    @Test
    fun mimeRecordDecodesAsBinaryWithItsMimeType() {
        val payload = byteArrayOf(0x7B, 0x7D)

        val record = decodeNdefRecord(
            tnf = NdefRecord.TNF_MIME_MEDIA,
            type = "application/json".toByteArray(Charsets.US_ASCII),
            payload = payload,
            platformUri = { null },
            platformMimeType = { "application/json" },
        )

        val binary = assertIs<LightNfcRecord.Binary>(record)
        assertEquals("application/json", binary.mimeType)
        assertContentEquals(payload, binary.bytes)
    }

    @Test
    fun absoluteUriRecordDecodesAsUri() {
        val record = decodeNdefRecord(
            tnf = NdefRecord.TNF_ABSOLUTE_URI,
            type = "http://example.com/pay".toByteArray(Charsets.US_ASCII),
            payload = byteArrayOf(),
            platformUri = { "http://example.com/pay" },
            platformMimeType = { null },
        )

        assertEquals(LightNfcRecord.Uri("http://example.com/pay"), record)
    }

    @Test
    fun wellKnownUriRecordDecodesAsUri() {
        val record = decodeNdefRecord(
            tnf = NdefRecord.TNF_WELL_KNOWN,
            type = "U".toByteArray(Charsets.US_ASCII),
            payload = byteArrayOf(0x01) + "example.com".toByteArray(Charsets.US_ASCII),
            platformUri = { "http://www.example.com" },
            platformMimeType = { null },
        )

        assertEquals(LightNfcRecord.Uri("http://www.example.com"), record)
    }

    @Test
    fun smartPosterRecordDecodesAsItsEmbeddedUri() {
        val record = decodeNdefRecord(
            tnf = NdefRecord.TNF_WELL_KNOWN,
            type = "Sp".toByteArray(Charsets.US_ASCII),
            payload = byteArrayOf(),
            platformUri = { "https://example.com" },
            platformMimeType = { null },
        )

        assertEquals(LightNfcRecord.Uri("https://example.com"), record)
    }

    @Test
    fun textRecordWithLanguageTagDecodesAsText() {
        val record = decodeNdefRecord(
            tnf = NdefRecord.TNF_WELL_KNOWN,
            type = "T".toByteArray(Charsets.US_ASCII),
            payload = textPayload(languageTag = "en-US", value = "Hello"),
            platformUri = { null },
            platformMimeType = { "text/plain" },
        )

        assertEquals(LightNfcRecord.Text(value = "Hello", languageTag = "en-US"), record)
    }

    @Test
    fun utf16TextRecordDecodesAsText() {
        val record = decodeNdefRecord(
            tnf = NdefRecord.TNF_WELL_KNOWN,
            type = "T".toByteArray(Charsets.US_ASCII),
            payload = textPayload(languageTag = "en", value = "Hello", utf16 = true),
            platformUri = { null },
            platformMimeType = { "text/plain" },
        )

        assertEquals(LightNfcRecord.Text(value = "Hello", languageTag = "en"), record)
    }

    @Test
    fun textRecordWithEmptyPayloadFallsBackToBinary() {
        val record = decodeNdefRecord(
            tnf = NdefRecord.TNF_WELL_KNOWN,
            type = "T".toByteArray(Charsets.US_ASCII),
            payload = byteArrayOf(),
            platformUri = { null },
            platformMimeType = { "text/plain" },
        )

        val binary = assertIs<LightNfcRecord.Binary>(record)
        assertContentEquals(byteArrayOf(), binary.bytes)
    }

    @Test
    fun decodesUtf8TextRecord() {
        val record = decodeTextRecord(textPayload(languageTag = "en", value = "Hello"))

        assertEquals(LightNfcRecord.Text(value = "Hello", languageTag = "en"), record)
    }

    @Test
    fun decodesUtf16TextRecord() {
        val record = decodeTextRecord(textPayload(languageTag = "en", value = "Hello", utf16 = true))

        assertEquals(LightNfcRecord.Text(value = "Hello", languageTag = "en"), record)
    }

    @Test
    fun decodesMultiByteLanguageTag() {
        val record = decodeTextRecord(textPayload(languageTag = "en-US", value = "Hello"))

        assertEquals(LightNfcRecord.Text(value = "Hello", languageTag = "en-US"), record)
    }

    @Test
    fun decodesTextRecordWithEmptyValue() {
        val record = decodeTextRecord(textPayload(languageTag = "en", value = ""))

        assertEquals(LightNfcRecord.Text(value = "", languageTag = "en"), record)
    }

    @Test
    fun decodesNonAsciiTextRecord() {
        val record = decodeTextRecord(textPayload(languageTag = "ja", value = "こんにちは"))

        assertEquals(LightNfcRecord.Text(value = "こんにちは", languageTag = "ja"), record)
    }

    @Test
    fun rejectsEmptyPayload() {
        assertNull(decodeTextRecord(byteArrayOf()))
    }

    @Test
    fun rejectsPayloadTruncatedInsideItsLanguageTag() {
        val truncated = byteArrayOf(0x05, 'e'.code.toByte(), 'n'.code.toByte())

        assertNull(decodeTextRecord(truncated))
    }

    @Test
    fun rejectsPayloadWithOnlyAStatusByte() {
        assertNull(decodeTextRecord(byteArrayOf(0x02)))
    }

    @Test
    fun formatsSerialNumberAsUppercaseHex() {
        val id = byteArrayOf(0x04, 0xA2.toByte(), 0xB3.toByte(), 0x0F, 0x00, 0xFF.toByte())

        assertEquals("04A2B30F00FF", id.toHexSerial())
    }

    @Test
    fun formatsEmptySerialNumberAsEmptyString() {
        assertEquals("", byteArrayOf().toHexSerial())
    }
}
