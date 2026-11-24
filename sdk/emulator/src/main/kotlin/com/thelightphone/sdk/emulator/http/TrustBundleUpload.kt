package com.thelightphone.sdk.emulator.http

import com.thelightphone.sdk.trust.FileTrustPersistence
import com.thelightphone.sdk.trust.SignedTrustBundle
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import java.util.Base64
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_ENCODED_BUNDLE = ((FileTrustPersistence.MAX_BUNDLE_BYTES + 2) / 3) * 4
private const val MAX_ENCODED_SIGNATURE = ((FileTrustPersistence.MAX_SIGNATURE_BYTES + 2) / 3) * 4
private const val MAX_UPLOAD_BYTES = MAX_ENCODED_BUNDLE + MAX_ENCODED_SIGNATURE + 4096L

internal suspend fun readTrustBundleUpload(
    channel: ByteReadChannel,
    limit: Long = MAX_UPLOAD_BYTES,
): String? = channel.readRemaining(limit + 1).use { source ->
    if (source.request(limit + 1)) null else source.readString()
}

internal fun parseTrustBundleUpload(request: String): SignedTrustBundle {
    val body = Json.parseToJsonElement(request) as? JsonObject
        ?: throw IllegalArgumentException("Expected a JSON object")
    val bytes = body.requireString("bytesBase64")
    val signature = body.requireString("signatureBase64")
    val decoder = Base64.getDecoder()
    require(bytes.length <= MAX_ENCODED_BUNDLE && signature.length <= MAX_ENCODED_SIGNATURE) {
        "Encoded bundle or signature exceeds the size limit"
    }
    val decodedBytes = decoder.decode(bytes)
    val decodedSignature = decoder.decode(signature)
    require(
        decodedBytes.size <= FileTrustPersistence.MAX_BUNDLE_BYTES &&
            decodedSignature.size <= FileTrustPersistence.MAX_SIGNATURE_BYTES
    ) { "Bundle or signature exceeds the size limit" }
    return SignedTrustBundle(decodedBytes, decodedSignature)
}

private fun JsonObject.requireString(name: String): String {
    val value = this[name] as? JsonPrimitive
    require(value != null && value.isString) { "Expected a string for " + name }
    return value.content
}
