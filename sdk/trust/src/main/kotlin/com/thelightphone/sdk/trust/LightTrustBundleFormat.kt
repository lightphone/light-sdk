package com.thelightphone.sdk.trust

object LightTrustBundleFormat {
    const val SUPPORTED_SCHEMA_VERSION = 1

    val signedPayloadPrefix: ByteArray by lazy {
        val separator = checkNotNull(javaClass.getResourceAsStream("/bundle-domain-separator.txt")) {
            "missing trust bundle domain separator"
        }.bufferedReader().use { it.readText().trimEnd('\n', '\r') }
        separator.encodeToByteArray() + byteArrayOf(0)
    }
}
