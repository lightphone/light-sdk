package com.thelightphone.sdk.nfc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.util.Log
import com.thelightphone.sdk.SealedLightActivity

interface LightNfc {
    val availability: LightNfcAvailability

    fun newReader(config: LightNfcReaderConfig = LightNfcReaderConfig()): LightNfcReader
}

@JvmInline
value class DefaultLightNfc(
    private val sealedActivity: SealedLightActivity
) : LightNfc {
    override val availability: LightNfcAvailability
        get() = sealedActivity.activity.readNfcAvailability()

    override fun newReader(config: LightNfcReaderConfig): LightNfcReader =
        LightNfcReader(sealedActivity.activity, config)
}

internal fun Context.readNfcAvailability(): LightNfcAvailability {
    val adapter = NfcAdapter.getDefaultAdapter(this) ?: return LightNfcAvailability.Unsupported
    if (checkSelfPermission(Manifest.permission.NFC) != PackageManager.PERMISSION_GRANTED) {
        Log.e(TAG, "android.permission.NFC not granted — add it to permissions in lighttool.toml")
        return LightNfcAvailability.PermissionMissing
    }
    return if (adapter.isEnabled) LightNfcAvailability.Ready else LightNfcAvailability.Disabled
}

private const val TAG = "LightNfc"
