package com.thelightphone.sdk.nfc

import android.nfc.NfcAdapter
import android.os.Bundle

enum class LightNfcAvailability {
    Unsupported,
    PermissionMissing,
    Disabled,
    Ready;

    val isReady: Boolean
        get() = this == Ready
}

enum class LightNfcTechnology {
    NfcA,
    NfcB,
    NfcF,
    NfcV;

    companion object {
        val All: Set<LightNfcTechnology> = entries.toSet()
    }
}

data class LightNfcReaderConfig(
    val technologies: Set<LightNfcTechnology> = LightNfcTechnology.All,
    val skipNdefCheck: Boolean = false,
    val platformSounds: Boolean = true,
    val presenceCheckDelayMs: Int? = null,
) {
    init {
        require(technologies.isNotEmpty()) { "technologies must include at least one NFC technology" }
    }
}

internal fun LightNfcReaderConfig.toReaderFlags(): Int {
    var flags = 0
    if (LightNfcTechnology.NfcA in technologies) flags = flags or NfcAdapter.FLAG_READER_NFC_A
    if (LightNfcTechnology.NfcB in technologies) flags = flags or NfcAdapter.FLAG_READER_NFC_B
    if (LightNfcTechnology.NfcF in technologies) flags = flags or NfcAdapter.FLAG_READER_NFC_F
    if (LightNfcTechnology.NfcV in technologies) flags = flags or NfcAdapter.FLAG_READER_NFC_V
    if (skipNdefCheck) flags = flags or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    if (!platformSounds) flags = flags or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    return flags
}

internal fun LightNfcReaderConfig.toReaderExtras(): Bundle? =
    presenceCheckDelayMs?.let { delayMs ->
        Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, delayMs) }
    }

sealed interface LightNfcRecord {
    data class Text(val value: String, val languageTag: String) : LightNfcRecord

    data class Uri(val value: String) : LightNfcRecord

    class Binary(val mimeType: String?, val bytes: ByteArray) : LightNfcRecord
}

data class LightNfcTap(
    val serialNumber: String,
    val records: List<LightNfcRecord>,
) {
    val text: String?
        get() = records.filterIsInstance<LightNfcRecord.Text>().firstOrNull()?.value

    val uri: String?
        get() = records.filterIsInstance<LightNfcRecord.Uri>().firstOrNull()?.value
}
